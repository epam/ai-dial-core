package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.function.BuildUpstreamCacheFn;
import com.epam.aidial.core.server.function.CollectDeploymentsFn;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestStandardAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceDeploymentRequestFn;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.util.List;
import java.util.function.Supplier;

import static com.epam.aidial.core.server.Proxy.HEADER_CACHE_POLICY;
import static com.epam.aidial.core.server.Proxy.HEADER_UPSTREAM_ID;

@Slf4j
public class DeploymentPostController extends BaseDeploymentPostController {
    private final List<BaseRequestFunction<RequestObject>> enhancementFunctions;

    public DeploymentPostController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions = List.of(new CollectRequestStandardAttachmentsFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context),
                new EnhanceDeploymentRequestFn(proxy, context),
                new CollectRequestApplicationFilesFn(proxy, context),
                new BuildUpstreamCacheFn(proxy, context),
                new CollectDeploymentsFn(proxy, context));
    }

    @ApiOperation(
            method = "POST",
            path = "/openai/deployments/{deployment_name}/completions",
            operationId = "createCompletion",
            tags = {"LLM"},
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME),
                    @ApiParameter(name = "api-version", in = ParameterIn.QUERY, required = true,
                            description = OpenApiDescriptions.API_VERSION, example = "2024-10-21"),
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json", schema = String.class),
                    @ApiParameter(name = HEADER_CACHE_POLICY, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.CACHE_POLICY,
                            allowableValues = {"availability-priority", "cache-priority"}),
                    @ApiParameter(name = HEADER_UPSTREAM_ID, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.UPSTREAM_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionResponse"), contentTypes = {"application/json"}),
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionStreamResponse"), contentTypes = {"text/event-stream"}),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 415),
                    @ApiResponse(code = 429, description = "Rate limit exceeded", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 503)
            })
    @ApiOperation(
            method = "POST",
            path = "/openai/deployments/{deployment_name}/chat/completions",
            operationId = "sendChatCompletionRequest",
            tags = {"LLM"},
            requestBody = @ApiSchema(schemaRef = "ChatCompletionRequest"),
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME),
                    @ApiParameter(name = "api-version", in = ParameterIn.QUERY, required = true,
                            description = OpenApiDescriptions.API_VERSION, example = "2024-10-21"),
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json"),
                    @ApiParameter(name = HEADER_CACHE_POLICY, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.CACHE_POLICY,
                            allowableValues = {"availability-priority", "cache-priority"}),
                    @ApiParameter(name = HEADER_UPSTREAM_ID, in = ParameterIn.HEADER,
                            description = OpenApiDescriptions.UPSTREAM_ID)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionResponse")),
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "CreateChatCompletionStreamResponse"), contentTypes = {"text/event-stream"}),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 415),
                    @ApiResponse(code = 429, description = "Rate limit exceeded", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 503)
            })
    @ApiOperation(
            method = "POST",
            path = "/openai/deployments/{deployment_name}/embeddings",
            operationId = "createEmbedding",
            tags = {"LLM"},
            requestBody = @ApiSchema(schemaRef = "EmbeddingsRequest"),
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME),
                    @ApiParameter(name = "api-version", in = ParameterIn.QUERY, required = true,
                            description = OpenApiDescriptions.API_VERSION, example = "2023-12-01-preview"),
                    @ApiParameter(name = "Content-Type", in = ParameterIn.HEADER, required = true,
                            description = "Must be application/json")
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(schemaRef = "EmbeddingResponse")),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 415),
                    @ApiResponse(code = 429, description = "Rate limit exceeded", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                    @ApiResponse(code = 503)
            })
    public Future<?> handle(String deploymentId) {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!Strings.CI.contains(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }
        // handle a special deployment `interceptor`
        if ("interceptor".equals(deploymentId)) {
            // move to next interceptor
            int nextIndex = context.getApiKeyData().getInterceptorIndex() + 1;
            return handleInterceptor(nextIndex);
        }
        return handleDeployment(deploymentId);
    }

    private Future<?> handleDeployment(String deploymentId) {
        return proxy.getTaskExecutor().submit(() -> proxy.getDeploymentService().findDeployment(context, deploymentId))
                .compose(dep -> proxy.getTaskExecutor().submit(() -> {
                    proxy.getConsentService().verifyUserConsent(context, dep);
                    return dep;
                }))
                .map(dep -> {
                    Features features = dep.getFeatures();
                    boolean isPerRequestKey = context.getApiKeyData().getPerRequestKey() != null;
                    if (features != null && Boolean.FALSE.equals(features.getAccessibleByPerRequestKey()) && isPerRequestKey) {
                        throw new PermissionDeniedException(String.format("Deployment %s is not accessible by %s", deploymentId, context.getApiKeyData().getSourceDeployment()));
                    }

                    if (dep instanceof Application app) {
                        dep = proxy.getApplicationSchemaService().modifyEndpointsForCustomApplication(app);
                    }

                    if (!dep.supportsInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS)) {
                        throw new HttpException(HttpStatus.SERVICE_UNAVAILABLE, "");
                    }

                    context.setTraceOperation("Send request to %s deployment".formatted(dep.getName()));
                    context.setDeployment(dep);
                    List<String> interceptors = proxy.getDeploymentService().getInterceptors(context, dep);
                    context.setInterceptors(interceptors);
                    return dep;
                })
                .compose(dep -> {
                    if (dep instanceof Model && !context.hasNextInterceptor()) {
                        return proxy.getRateLimiter().limit(context, dep);
                    } else {
                        return Future.succeededFuture(RateLimitResult.SUCCESS);
                    }
                })
                .compose(rateLimitResult -> {
                    Future<?> future;
                    if (rateLimitResult.status() == HttpStatus.OK) {
                        if (context.hasNextInterceptor()) {
                            context.setInitialDeployment(deploymentId);
                            future = handleInterceptor(0);
                        } else {
                            future = handleRateLimitSuccess();
                        }
                    } else {
                        handleRateLimitHit(deploymentId, rateLimitResult);
                        future = Future.succeededFuture();
                    }
                    return future;
                })
                .otherwise(error -> {
                    handleRequestError(deploymentId, error);
                    return null;
                });
    }

    private Future<?> handleInterceptor(int interceptorIndex) {
        List<String> interceptors = context.getInterceptors();
        if (interceptorIndex < interceptors.size()) {
            return new ChatCompletionInterceptorController(proxy, context, interceptorIndex).handle();
        } else { // all interceptors are completed we should call the initial deployment
            return handleDeployment(context.getApiKeyData().getInitialDeployment());
        }
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            respond(HttpStatus.FORBIDDEN, error.getMessage());
            log.warn("Forbidden deployment {}", deploymentId);
        } else if (error instanceof ResourceNotFoundException) {
            respond(HttpStatus.NOT_FOUND, error.getMessage());
            log.warn("Deployment not found {}", deploymentId, error);
        } else if (error instanceof HttpException e) {
            respond(e.getStatus(), e.getMessage());
            log.warn("Deployment error {}", deploymentId, error);
        } else {
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to process deployment: " + deploymentId);
            log.error("Failed to handle deployment {}", deploymentId, error);
        }
    }

    private Future<?> handleRateLimitSuccess() {
        log.info("Received request from client. Deployment: {}. Headers: {}",
                context.getDeployment().getName(),
                context.getRequest().headers().size());

        setupProxyApiKeyData(new ApiKeyData());
        return proxy.getTokenStatsTracker().startSpan(context).map(ignore -> {
            context.getRequest().body()
                    .onSuccess(body -> proxy.getTaskExecutor().submit(() -> {
                        handleRequestBody(body);
                        return null;
                    }).onFailure(error -> handleRequestError(context.getDeployment().getName(), error)))
                    .onFailure(this::handleRequestBodyError);
            return null;
        });
    }

    private void setupProxyApiKeyData(ApiKeyData proxyApiKeyData) {
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    private void handleRateLimitHit(String deploymentId, RateLimitResult result) {
        try {
            result.throwIfError();
        } catch (HttpException e) {
            respond(e);
            log.warn("Rate limit error {}. Deployment: {}", result.errorMessage(), deploymentId);
        }
    }

    @SneakyThrows
    private void sendRequest() {
        if (nextUpstream()) {
            createProxyRequest(InterfaceType.OPENAI_CHAT_COMPLETIONS)
                    .onSuccess(this::handleProxyRequest)
                    .onFailure(this::handleProxyConnectionError);
        }
    }

    @VisibleForTesting
    void handleRequestBody(Buffer requestBody) {
        Deployment deployment = context.getDeployment();
        log.info("Received body from client. Deployment: {}. Length: {}",
                deployment.getName(), requestBody.length());

        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());

        try {
            RequestObject request = new ChatCompletionRequest(ProxyUtil.parseObject(requestBody));
            context.setStreamingRequest(request.isStreaming());
            if (ProxyUtil.processChain(request, enhancementFunctions)) {
                context.setRequestBody(Buffer.buffer(request.serialize()));
            }
            proxy.getApiKeyStore().assignPerRequestApiKey(context.getProxyApiKeyData());
        } catch (Throwable e) {
            if (e instanceof HttpException httpException) {
                respond(httpException.getStatus(), httpException.getMessage());
            } else {
                respond(HttpStatus.BAD_REQUEST);
            }
            log.warn("Can't process JSON request body. Error:", e);
            return;
        }

        String upstreamId = context.getRequest().headers().get(HEADER_UPSTREAM_ID);
        UpstreamRoute upstreamRoute;
        try {
            upstreamRoute = proxy.getUpstreamRouteProvider().get(deployment, context.getCacheBreakpointContext(),
                    dep -> dep.resolveEndpoint(InterfaceType.OPENAI_CHAT_COMPLETIONS), upstreamId);
        } catch (HttpException e) {
            respond(e.getStatus(), e.getMessage());
            return;
        }
        context.setUpstreamRoute(upstreamRoute);

        sendRequest();
    }

    /**
     * Called when proxy connected to the origin.
     */
    @VisibleForTesting
    void handleProxyRequest(HttpClientRequest proxyRequest) {
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        sendProxyRequest(proxyRequest, Upstream::getEndpoint)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        Upstream currentUpstream = upstreamRoute.get();
        log.info("Received header from origin. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Headers: {}. Upstream.extraData: {}",
                context.getDeployment().getName(),
                context.getProxyRequestUri(),
                currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size(), currentUpstream == null ? "N/A" : currentUpstream.getExtraData());

        int responseStatusCode = proxyResponse.statusCode();
        if (isRetriableError(responseStatusCode)) {
            upstreamRoute.fail(proxyResponse);
            sendRequest(); // try next
            return;
        }

        if (responseStatusCode == 200) {
            upstreamRoute.succeed(proxyResponse, context.getDeployment());
        } else if (!HttpStatus.fromStatusCode(responseStatusCode).is4xx()) {
            // mark the upstream as failed
            // and the next time we will select another one
            upstreamRoute.fail(proxyResponse);
        }

        Supplier<BufferingReadStream.BaseEventListener> eventListenerSupplier = () ->
                new ChatCompletionSseListener(new CollectResponseChatCompletionAttachmentsFn(proxy, context));
        BufferingReadStream responseStream = createResponseStream(proxyResponse, eventListenerSupplier);

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(upstreamRoute.getAttemptCount()));

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(responseStream))
                .onFailure(error -> handleResponseError(error, responseStream));
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    @VisibleForTesting
    void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = responseStream.getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        Future<Void> tokenUsageFuture = collectTokenUsage(responseBody);

        Future<Void> handleResponseFuture = tokenUsageFuture.transform(result -> {
            if (result.failed()) {
                log.warn("Failed to collect token usage", result.cause());
            }
            return collectResponseAttachments(responseBody, new CollectResponseChatCompletionAttachmentsFn(proxy, context));
        });

        handleResponseFuture.onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect attachments from response", result.cause());
            }
            completeProxyResponse(responseStream);
        });
    }

    private void completeProxyResponse(BufferingReadStream responseStream) {
        HttpServerResponse response = context.getResponse();
        responseStream.end(response);

        String assembledStreamingResponse = null;
        if (isEventStreamResponse(context.getProxyResponse())) {
            assembledStreamingResponse = AnalyticsLogContext.assembleStreamingChatCompletionsResponse(context.getResponseBody());
        }
        proxy.getLogStore().save(AnalyticsLogContext.from(context, assembledStreamingResponse));
        Upstream currentUpstream = context.getUpstreamRoute().get();
        log.info("Sent response to client. Deployment: {}. Endpoint: {}. Upstream: {}. Length: {}."
                        + " Timing: {} (body={}, connect={}, header={}, body={}). Tokens: {}. Upstream.extraData: {}",
                context.getDeployment().getName(),
                context.getProxyRequestUri(),
                currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                context.getResponseBody().length(),
                context.getResponseBodyTimestamp() - context.getRequestTimestamp(),
                context.getRequestBodyTimestamp() - context.getRequestTimestamp(),
                context.getProxyConnectTimestamp() - context.getRequestBodyTimestamp(),
                context.getProxyResponseTimestamp() - context.getProxyConnectTimestamp(),
                context.getResponseBodyTimestamp() - context.getProxyResponseTimestamp(),
                context.getTokenUsage() == null ? "N/A" : context.getTokenUsage(),
                currentUpstream == null ? "N/A" : currentUpstream.getExtraData());

        finalizeRequest();
    }

    /**
     * Called when proxy failed to receive response header from origin.
     */
    private void handleProxyResponseError(Throwable error) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        log.warn("Proxy failed to receive response header from origin. Deployment: {}. Address: {}. Error:",
                context.getDeployment().getName(),
                context.getProxyRequest().connection().remoteAddress(),
                error);
        sendRequest(); // try next
    }

    public static class ChatCompletionSseListener extends BufferingReadStream.BaseEventListener {

        public static final String CHAT_COMPLETION_FINAL_MESSAGE = "[DONE]";

        public ChatCompletionSseListener(BaseResponseFunction function) {
            super(List.of(function));
        }

        @Override
        protected boolean isLastEvent(SseEvent event, JsonNode data) {
            return isFinalEvent(event);
        }

        @Override
        protected boolean skipEvent(SseEvent event) {
            return isFinalEvent(event);
        }

        private static boolean isFinalEvent(SseEvent event) {
            return CHAT_COMPLETION_FINAL_MESSAGE.equals(event.getData());
        }
    }
}