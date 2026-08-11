package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.BaseDeploymentPostController;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.FeaturesData;
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

/**
 * Shared plumbing for the Anthropic Messages API controllers ({@link MessagesController} and
 * {@link MessagesCountTokensController}): the whole {@link #handle()} flow — body parsing,
 * deployment resolution + the {@code anthropicMessages} 503 gate, the enhancement chain,
 * upstream-route preparation and the send/retry loop. Subclasses implement
 * {@link #processResponse(HttpClientResponse)} and may override {@link #verifyLimit()}.
 */
@Slf4j
abstract class MessagesBaseController extends BaseDeploymentPostController {

    /**
     * Path markers an adapter's {@code base_url} carries when it translates the Anthropic Messages request
     * into another API.
     */
    private static final List<String> TRANSLATION_MARKERS = List.of("to-chat-completions", "to-responses");

    protected final List<BaseRequestFunction<RequestObject>> enhancementFunctions;

    /**
     * The {@code model} value as it arrived in the request body, i.e. the DIAL deployment id. Captured before
     * {@link EnhanceDeploymentRequestFn} rewrites the body model to the deployment's {@code overrideName}, and
     * forwarded upstream as {@link Proxy#HEADER_DEPLOYMENT_ID}.
     */
    private String deploymentId;

    protected MessagesBaseController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions = List.of(
                new CollectRequestStandardAttachmentsFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context),
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
                .map(MessagesBaseController::parseBody)
                .compose(request -> {
                    String model = request.getModel();
                    deploymentId = model;
                    return proxy.getTaskExecutor().submit(() -> setupDeployment(model))
                            .compose(ignore -> verifyLimit())
                            .compose(ignore -> proxy.getTokenStatsTracker().startSpan(context)
                                    .map(ignored -> handleRequestBody(request)))
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

    protected static MessagesApiRequest parseBody(Buffer body) {
        log.info("Received body from client. Length: {}", body.length());
        try {
            ObjectNode tree = ProxyUtil.parseObject(body);
            return new MessagesApiRequest(tree);
        } catch (IOException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    protected Void setupDeployment(String model) {
        Deployment deployment = proxy.getDeploymentService().findDeployment(context, model);
        proxy.getConsentService().verifyUserConsent(context, deployment);

        Features features = deployment.getFeatures();
        boolean isPerRequestKey = context.getApiKeyData().getPerRequestKey() != null;
        if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
            throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", model, context.getApiKeyData().getSourceDeployment()));
        }

        if (deployment instanceof Application application) {
            deployment = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(application);
        }

        if (!deployment.supportsInterface(InterfaceType.ANTHROPIC_MESSAGES)) {
            throw new HttpException(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Anthropic messages not supported for this deployment type"
            );
        }

        context.setTraceOperation("Send request to %s deployment".formatted(deployment.getName()));
        context.setDeployment(deployment);

        return null;
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
                        dep -> dep.resolveEndpoint(InterfaceType.ANTHROPIC_MESSAGES), upstreamId);

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
            createProxyRequest(InterfaceType.ANTHROPIC_MESSAGES)
                    .onSuccess(this::handleProxyRequest)
                    .onFailure(this::handleProxyConnectionError);
        }
    }

    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        sendProxyRequest(proxyRequest, Upstream::getEndpoint)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * The body's {@code model} may have been replaced by {@code overrideName}, so the deployment id is
     * carried to the adapter out of band. Translating adapters additionally receive the deployment features,
     * which tell them which parameters the target API accepts.
     */
    @Override
    protected void enrichProxyRequestHeaders(HttpClientRequest proxyRequest) {
        proxyRequest.putHeader(Proxy.HEADER_DEPLOYMENT_ID, deploymentId);

        if (isTranslatingUpstream(context.getProxyRequestUri())) {
            proxyRequest.putHeader(Proxy.HEADER_DEPLOYMENT_FEATURES,
                    ProxyUtil.convertToString(FeaturesData.createDeploymentFeatures(context.getDeployment())));
        }
    }

    /**
     * A translating adapter converts the Anthropic Messages request into another API before calling the
     * upstream; it is recognized by the translation marker its {@code base_url} carries.
     */
    private static boolean isTranslatingUpstream(String uri) {
        return uri != null && TRANSLATION_MARKERS.stream().anyMatch(uri::contains);
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
