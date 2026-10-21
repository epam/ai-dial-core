package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.BuiltinTranslator;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Translator;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.BaseDeploymentPostController;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectDeploymentsFn;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestStandardAttachmentsFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceDeploymentRequestFn;
import com.epam.aidial.core.server.function.request.MessagesApiRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Shared plumbing for the Anthropic Messages API controllers ({@link MessagesController} and
 * {@link MessagesCountTokensController}): the whole {@link #handle()} flow — body parsing,
 * deployment resolution + the {@code anthropicMessages} 503 gate, the enhancement chain,
 * upstream-route preparation and the send/retry loop. Subclasses implement
 * {@link #processResponse(HttpClientResponse)} and may override {@link #verifyLimit()}.
 */
@Slf4j
abstract class MessagesBaseController extends BaseDeploymentPostController {

    private static final String HEADER_ANTHROPIC_VERSION = "anthropic-version";
    private static final String HEADER_ANTHROPIC_BETA = "anthropic-beta";

    protected final List<BaseRequestFunction<RequestObject>> enhancementFunctions;

    /**
     * The {@code model} value as it arrived in the request body, i.e. the DIAL deployment id. Captured before
     * {@link EnhanceDeploymentRequestFn} rewrites the body model to the deployment's {@code overrideName}, and
     * forwarded upstream as {@link Proxy#HEADER_DEPLOYMENT_ID}.
     */
    private String deploymentId;

    /**
     * The translation Core performs itself for this request, or null when the {@code anthropicMessages}
     * interface is forwarded as it is. Resolved with the deployment, and only for a controller that can serve
     * one ({@link #isBuiltinTranslationSupported()}).
     */
    protected BuiltinTranslator builtinTranslator;

    protected MessagesBaseController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions = buildEnhancementFunctions();
    }

    /**
     * The shared chain. {@link MessagesController} overrides this to append {@link
     * com.epam.aidial.core.server.function.BuildUpstreamCacheFn} — {@code count_tokens} does not
     * generate, so it must not build a cache breakpoint context.
     */
    protected List<BaseRequestFunction<RequestObject>> buildEnhancementFunctions() {
        return List.of(
                new CollectRequestStandardAttachmentsFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES),
                new EnhanceDeploymentRequestFn(proxy, context),
                new CollectRequestApplicationFilesFn(proxy, context),
                new CollectDeploymentsFn(proxy, context));
    }

    public Future<?> handle() {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }
        context.getRequest().body()
                .map(this::parseBody)
                .compose(request -> {
                    String model = request.getModel();
                    deploymentId = model;
                    return proxy.getTaskExecutor().submit(() -> proxy.getBlockingCallTracer().trace("deployment.resolve", () -> setupDeployment(model)))
                            .compose(ignore -> verifyLimit())
                            .compose(ignore -> proxy.getTokenStatsTracker().startSpan(context)
                                    .compose(ignored -> proxy.getTaskExecutor().submit(() -> handleRequestBody(request))))
                            .otherwise(error -> handleRequestError(model, error));
                })
                .onFailure(this::handleRequestBodyError);

        return Future.succeededFuture();
    }

    /**
     * Rate-limit gate before the upstream call. No-op by default: count_tokens does not generate,
     * so it charges no limits.
     */
    protected Future<Void> verifyLimit() {
        return Future.succeededFuture();
    }

    @SneakyThrows
    private Void handleRequestBody(RequestObject request) {
        context.setStreamingRequest(request.isStreaming());
        prepareUpstreamRoute(request);
        sendRequest();

        return null;
    }

    protected MessagesApiRequest parseBody(Buffer body) {
        log.info("Received body from client. Length: {}", body.length());
        try {
            ObjectNode tree = ProxyUtil.parseObject(body);
            return new MessagesApiRequest(tree);
        } catch (IOException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * Whether this controller serves a request through a builtin translator. One that does not answers
     * {@code 503} for such a deployment, as for any interface it has no endpoint for.
     */
    protected boolean isBuiltinTranslationSupported() {
        return false;
    }

    protected Void setupDeployment(String model) {
        Deployment deployment = proxy.getDeploymentService().findDeployment(context, model);
        verifyAccess(deployment, model, InterfaceType.ANTHROPIC_MESSAGES);

        if (deployment instanceof Application application) {
            deployment = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(application);
        }

        Map<String, Translator> translators = context.getConfig().getTranslators();
        builtinTranslator = isBuiltinTranslationSupported()
                ? DeploymentEndpointUtil.resolveBuiltinTranslator(deployment, InterfaceType.ANTHROPIC_MESSAGES, translators)
                : null;
        // a builtin translator is served by the deployment's own interface for what it translates to
        InterfaceType servedType = builtinTranslator == null ? InterfaceType.ANTHROPIC_MESSAGES : builtinTranslator.getOut();
        if (DeploymentEndpointUtil.resolveServingEndpoint(deployment, servedType, translators) == null) {
            throw new HttpException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Anthropic messages not supported for this deployment type"
            );
        }

        // the caller reaches the interface the request is translated to as well, which a translator service's
        // pass back to Core is checked against
        if (builtinTranslator != null) {
            verifyAccess(deployment, model, builtinTranslator.getOut());
        }

        // the interceptors of a translated request run on the pass a translator service makes back to Core,
        // which a builtin translator does not make: serving it without them would skip them silently
        if (builtinTranslator != null && !proxy.getDeploymentService().getInterceptors(context, deployment).isEmpty()) {
            throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE,
                    "The builtin translator does not run interceptors yet: serve this deployment through a translator service");
        }

        context.setTraceOperation("Send request to %s deployment".formatted(deployment.getName()));
        context.setDeployment(deployment);

        return null;
    }

    private void verifyAccess(Deployment deployment, String model, InterfaceType type) {
        proxy.getConsentService().verifyUserConsent(context, deployment, type);
        Features features = deployment.resolveFeatures(type);
        boolean isPerRequestKey = context.getApiKeyData().getPerRequestKey() != null;
        if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
            throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", model, context.getApiKeyData().getSourceDeployment()));
        }
    }

    /**
     * Runs the enhancement chain, assigns a per-request key, serializes the (possibly model-overridden)
     * body and resolves the upstream route via the {@code anthropicMessages} base_url.
     */
    @SneakyThrows
    protected void prepareUpstreamRoute(RequestObject request) {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);

        ProxyUtil.processChain(request, enhancementFunctions);
        // Enhancement functions update the api key, and it should be saved after that
        proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);

        context.setRequestBody(Buffer.buffer(request.serialize()));

        Deployment deployment = context.getDeployment();
        String upstreamId = context.getRequest().headers().get(Proxy.HEADER_UPSTREAM_ID);
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider()
                .get(deployment, context.getCacheBreakpointContext(),
                        dep -> DeploymentEndpointUtil.resolveServingEndpoint(dep, InterfaceType.ANTHROPIC_MESSAGES,
                                context.getConfig().getTranslators()), upstreamId);

        context.setRequestBodyTimestamp(System.currentTimeMillis());
        context.setUpstreamRoute(upstreamRoute);
    }

    protected void sendRequest() {
        if (nextUpstream()) {
            Upstream upstream = context.getUpstreamRoute().get();
            if (upstream.getId() == null || upstream.getId().isBlank()) {
                respond(HttpStatus.SERVICE_UNAVAILABLE, "Upstream is missing required id");
                return;
            }
            Future<HttpClientRequest> proxyRequest = builtinTranslator == null
                    ? createProxyRequest(InterfaceType.ANTHROPIC_MESSAGES)
                    : createProxyRequest(DeploymentEndpointUtil.resolveRequestUri(context.getDeployment(), builtinTranslator.getOut(),
                            context.getConfig().getTranslators(), DeploymentEndpointUtil.OPENAI_RESPONSES_BASE_PATH, null));
            proxyRequest.onSuccess(this::handleProxyRequest)
                    .onFailure(this::handleProxyConnectionError);
        }
    }

    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        sendProxyRequest(proxyRequest, builtinTranslator == null ? InterfaceType.ANTHROPIC_MESSAGES : builtinTranslator.getOut())
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * The body's {@code model} may have been replaced by {@code overrideName}, so the deployment id is
     * carried to the adapter out of band. A translated request goes out as the Responses pass a translator
     * service would make, without the Anthropic headers, which mean nothing to a Responses upstream.
     */
    @Override
    protected void enrichProxyRequestHeaders(HttpClientRequest proxyRequest) {
        if (builtinTranslator == null) {
            proxyRequest.putHeader(Proxy.HEADER_DEPLOYMENT_ID, deploymentId);
            return;
        }
        proxyRequest.headers()
                .remove(HEADER_ANTHROPIC_VERSION)
                .remove(HEADER_ANTHROPIC_BETA);
    }

    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        int responseStatusCode = proxyResponse.statusCode();
        if (isRetriableError(responseStatusCode)) {
            upstreamRoute.fail(proxyResponse);
            sendRequest(); // try next
            return;
        }

        if (responseStatusCode == 200) {
            upstreamRoute.succeed(proxyResponse, context.getDeployment());
        } else if (!HttpStatus.fromStatusCode(responseStatusCode).is4xx()) {
            // mark the upstream as failed, so the next time we select another one
            upstreamRoute.fail(proxyResponse);
        }

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());

        processResponse(proxyResponse);
    }

    private void handleProxyResponseError(Throwable error) {
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        context.getUpstreamRoute().fail(HttpStatus.BAD_GATEWAY);
        log.warn("Proxy failed to receive response header from origin. Deployment: {}. Address: {}. Error:",
                context.getDeployment().getName(),
                context.getProxyRequest().connection().remoteAddress(),
                error);

        sendRequest(); // try next
    }

    /**
     * Route-specific response handling, invoked after the shared retry/succeed/fail bookkeeping.
     */
    protected abstract void processResponse(HttpClientResponse proxyResponse);

    protected Void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            respond(HttpStatus.FORBIDDEN, error.getMessage());
            log.warn("Forbidden deployment {}", deploymentId);
        } else if (error instanceof ResourceNotFoundException) {
            respond(HttpStatus.NOT_FOUND, error.getMessage());
            log.warn("Deployment not found {}", deploymentId, error);
        } else if (error instanceof HttpException httpException) {
            respond(httpException);
            log.warn("Deployment error {}", deploymentId, error);
        } else {
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
            log.error("Failed to handle deployment {}", deploymentId, error);
        }

        return null;
    }
}
