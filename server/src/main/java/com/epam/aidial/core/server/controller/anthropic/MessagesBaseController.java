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
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectDeploymentsFn;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestStandardAttachmentsFn;
import com.epam.aidial.core.server.function.ResolveResourceDependenciesFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceDeploymentRequestFn;
import com.epam.aidial.core.server.function.request.MessagesApiRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.service.DeploymentService;
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
import java.util.ArrayList;
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

    protected final List<BaseRequestFunction<RequestObject>> enhancementFunctions;

    protected MessagesBaseController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions = buildEnhancementFunctions();
    }

    /**
     * The shared chain. Subclasses may override {@link #addFunctionsBeforeResolver} to inject functions that must run
     * after {@link CollectDeploymentsFn} and before the resolver — {@link MessagesController} does so to add
     * {@link com.epam.aidial.core.server.function.BuildUpstreamCacheFn}, while {@code count_tokens} inherits the base
     * chain unchanged.
     */
    protected List<BaseRequestFunction<RequestObject>> buildEnhancementFunctions() {
        List<BaseRequestFunction<RequestObject>> functions = new ArrayList<>(List.of(
                new CollectRequestStandardAttachmentsFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES),
                new EnhanceDeploymentRequestFn(proxy, context),
                new CollectRequestApplicationFilesFn(proxy, context)));
        addFunctionsBeforeCollectDeployments(functions);
        functions.add(new CollectDeploymentsFn(proxy, context));
        functions.add(new ResolveResourceDependenciesFn<>(proxy, context));
        return functions;
    }

    /** Override to insert functions that run before {@link CollectDeploymentsFn} and the resolver. */
    protected void addFunctionsBeforeCollectDeployments(List<BaseRequestFunction<RequestObject>> functions) {
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
                    return proxy.getTaskExecutor().submit(() -> proxy.getBlockingCallTracer().trace("deployment.resolve", () -> setupDeployment(model)))
                            .compose(ignore -> decisions.decideIfRouter(InterfaceType.ANTHROPIC_MESSAGES,
                                    Future.succeededFuture(context.getOriginalRequestBody()), this::reenterWithCandidate))
                            .compose(decided -> decided ? serveCandidate(request) : proceedToDeployment(request))
                            .otherwise(error -> handleRequestError(model, error));
                })
                .onFailure(this::handleRequestBodyError);

        return Future.succeededFuture();
    }

    /**
     * Serves the decision's current candidate as if the client had called it, with the body {@code model} set to
     * its name so the chain applies its override name.
     */
    private Future<Void> serveCandidate(MessagesApiRequest request) {
        String candidate = context.getDecision().current();
        return proxy.getTaskExecutor().submit(() -> proxy.getBlockingCallTracer().trace("deployment.resolve", () -> setupDeployment(candidate)))
                .compose(ignore -> {
                    request.setModel(candidate);
                    return proceedToDeployment(request);
                })
                .otherwise(error -> handleRequestError(candidate, error));
    }

    private void reenterWithCandidate() {
        // the chain of the failed candidate rewrote the parsed request, so the fallback starts from the client's bytes
        serveCandidate(parseBody(context.getOriginalRequestBody()));
    }

    private Future<Void> proceedToDeployment(MessagesApiRequest request) {
        return verifyLimit()
                .compose(ignore -> {
                    if (context.getDecision() != null) {
                        context.getDecision().setResolving(false);
                    }
                    return proxy.getTokenStatsTracker().startSpan(context);
                })
                .compose(ignored -> proxy.getTaskExecutor().submit(() -> handleRequestBody(request)));
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
            context.setRequestBodyWithTimestamp(body);
            context.setOriginalRequestBody(body);
            ObjectNode tree = ProxyUtil.parseObject(body);
            return new MessagesApiRequest(tree);
        } catch (IOException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    protected Void setupDeployment(String model) {
        Deployment deployment = proxy.getDeploymentService().findCallableDeployment(context, model);
        proxy.getConsentService().verifyUserConsent(context, deployment, InterfaceType.ANTHROPIC_MESSAGES);

        Features features = deployment.resolveFeatures(InterfaceType.ANTHROPIC_MESSAGES);
        boolean isPerRequestKey = context.getApiKeyData().getPerRequestKey() != null;
        if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey
                && !DeploymentService.isLent(context, deployment)) {
            throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", model, context.getApiKeyData().getSourceDeployment()));
        }

        if (deployment instanceof Application application) {
            deployment = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(application);
        }

        if (DeploymentEndpointUtil.resolveServingEndpoint(deployment, InterfaceType.ANTHROPIC_MESSAGES,
                context.getConfig().getTranslators()) == null) {
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
            createProxyRequest(InterfaceType.ANTHROPIC_MESSAGES)
                    .onSuccess(this::handleProxyRequest)
                    .onFailure(this::handleProxyConnectionError);
        }
    }

    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        sendProxyRequest(proxyRequest, InterfaceType.ANTHROPIC_MESSAGES)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
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
        error = decisions.nextCandidateOrError(error, e -> handleRequestError(deploymentId, e));
        if (error == null) {
            return null;
        }
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
