package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.function.BuildUpstreamCacheFn;
import com.epam.aidial.core.server.function.CollectChatCompletionUsageFn;
import com.epam.aidial.core.server.function.CollectDeploymentsFn;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestSkillsFn;
import com.epam.aidial.core.server.function.CollectRequestStandardAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.function.StripUsagePerModelFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.enhancement.EnhanceDeploymentRequestFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UsagePerModelInjector;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static com.epam.aidial.core.server.Proxy.HEADER_UPSTREAM_ID;

/**
 * Shared chat-completions request/response handling reused by every controller that ultimately serves
 * the OpenAI Chat Completions response shape - today {@link DeploymentPostController} (deployment
 * addressed via the URL path) and {@link ChatCompletionsController} (deployment addressed via the
 * request body's {@code model} field).
 */
@Slf4j
public class BaseChatCompletionController extends BaseDeploymentPostController {

    public BaseChatCompletionController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    /**
     * The interface the request targets. Default covers the deployment-less chat-completions route;
     * {@link DeploymentPostController} overrides it since that controller also serves
     * {@code /completions} and {@code /embeddings} from the same class.
     */
    protected InterfaceType requestedInterface() {
        return InterfaceType.OPENAI_CHAT_COMPLETIONS;
    }

    /**
     * Whether the current request/response is on the chat-completions shape (as opposed to, e.g.
     * {@code /completions} or {@code /embeddings}, which {@link DeploymentPostController} also serves).
     * Default {@code true} since every other controller reusing this class only ever serves that shape.
     */
    protected boolean isChatCompletionsPath() {
        return true;
    }

    @Override
    protected InterfaceType interfaceType() {
        return requestedInterface();
    }

    private List<BaseRequestFunction<RequestObject>> buildEnhancementFunctions() {
        return List.of(new CollectRequestStandardAttachmentsFn(proxy, context),
                new CollectRequestSkillsFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context, requestedInterface()),
                new EnhanceDeploymentRequestFn(proxy, context),
                new CollectRequestApplicationFilesFn(proxy, context),
                new BuildUpstreamCacheFn(proxy, context, requestedInterface()),
                new CollectDeploymentsFn(proxy, context));
    }

    /**
     * Runs the chat-completions enhancement chain over an already-parsed request body, assigns the
     * per-request API key, resolves the upstream route and sends the request. Shared tail of every
     * controller's request-body handling once the deployment is resolved and the body is parsed.
     */
    @SneakyThrows
    protected void processChatCompletionsRequestBody(RequestObject request) {
        Deployment deployment = context.getDeployment();
        context.setStreamingRequest(request.isStreaming());
        if (ProxyUtil.processChain(request, buildEnhancementFunctions())) {
            context.setRequestBody(Buffer.buffer(request.serialize()));
        }
        proxy.getApiKeyStore().assignPerRequestApiKey(context.getProxyApiKeyData());

        String upstreamId = context.getRequest().headers().get(HEADER_UPSTREAM_ID);
        InterfaceType type = requestedInterface();
        UpstreamRoute upstreamRoute;
        try {
            upstreamRoute = proxy.getUpstreamRouteProvider().get(deployment, context.getCacheBreakpointContext(),
                    dep -> DeploymentEndpointUtil.resolveServingEndpoint(dep, type, context.getConfig().getTranslators()),
                    upstreamId);
        } catch (HttpException e) {
            respond(e.getStatus(), e.getMessage());
            return;
        }
        context.setUpstreamRoute(upstreamRoute);

        sendRequest();
    }

    @SneakyThrows
    protected void sendRequest() {
        if (nextUpstream()) {
            createProxyRequest(requestedInterface())
                    .onSuccess(this::handleProxyRequest)
                    .onFailure(this::handleProxyConnectionError);
        }
    }

    /**
     * Called when proxy connected to the origin.
     */
    @VisibleForTesting
    void handleProxyRequest(HttpClientRequest proxyRequest) {
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        sendProxyRequest(proxyRequest, requestedInterface())
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

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());

        // statistics.usage_per_model is only in scope for /chat/completions (not /completions or
        // /embeddings, which DeploymentPostController also serves from this base class); a non-SSE
        // response has to be buffered to inject into it, since today's bytes are otherwise piped to
        // the client as they arrive.
        if (isChatCompletionsPath() && !isEventStreamContentType(proxyResponse)) {
            proxyResponse.body()
                    .compose(body -> handleNonStreamingChatCompletionResponse(proxyResponse, body))
                    .onFailure(this::handleProxyConnectionError);
            return;
        }

        Supplier<BufferingReadStream.BaseEventListener> eventListenerSupplier = () ->
                new ChatCompletionSseListener(isChatCompletionsPath()
                        ? List.of(new StripUsagePerModelFn(proxy, context), new CollectResponseChatCompletionAttachmentsFn(proxy, context),
                                new CollectChatCompletionUsageFn(proxy, context))
                        : List.of(new CollectResponseChatCompletionAttachmentsFn(proxy, context)));
        BufferingReadStream responseStream = createResponseStream(proxyResponse, eventListenerSupplier);

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

    private boolean isEventStreamContentType(HttpClientResponse proxyResponse) {
        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        return Strings.CI.contains(contentType, "text/event-stream");
    }

    /**
     * Non-streaming /chat/completions: buffers the body (mirroring {@code ResponsesController}'s
     * non-streaming handling) so {@code statistics.usage_per_model} can be injected before the
     * client sees a byte of it.
     */
    private Future<Void> handleNonStreamingChatCompletionResponse(HttpClientResponse proxyResponse, Buffer body) {
        context.setResponseBody(body);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        HttpServerResponse response = context.getResponse();
        ProxyUtil.copyResponse(response, proxyResponse);
        response.setChunked(false);
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().getAttemptCount()));

        return collectTokenUsage(body)
                .transform(result -> {
                    if (result.failed()) {
                        log.warn("Failed to collect token usage", result.cause());
                    }
                    return collectResponseAttachments(body, new CollectResponseChatCompletionAttachmentsFn(proxy, context));
                })
                .transform(result -> {
                    if (result.failed()) {
                        log.warn("Failed to collect attachments from response", result.cause());
                    }
                    Buffer rewritten = maybeInjectUsagePerModel(body);
                    response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(rewritten.length()));
                    response.end(rewritten);
                    finishAndLog(null);
                    return Future.<Void>succeededFuture();
                });
    }

    private Buffer buildUsagePerModelChunk(List<UsagePerModel> usagePerModel) {
        ObjectNode chunk = ProxyUtil.MAPPER.createObjectNode();
        chunk.put("object", "chat.completion.chunk");
        chunk.putArray("choices");
        UsagePerModelInjector.inject(chunk, usagePerModel);
        return Buffer.buffer("data: " + ProxyUtil.convertToString(chunk) + "\n\n");
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
        if (isChatCompletionsPath() && isEventStreamResponse(context.getProxyResponse())) {
            List<UsagePerModel> usagePerModel = context.getUsagePerModel();
            if (usagePerModel != null && !usagePerModel.isEmpty()) {
                response.write(buildUsagePerModelChunk(usagePerModel));
            }
        }
        responseStream.end(response);

        String assembledStreamingResponse = null;
        if (isEventStreamResponse(context.getProxyResponse())) {
            assembledStreamingResponse = AnalyticsLogContext.assembleStreamingChatCompletionsResponse(context.getResponseBody());
        }
        finishAndLog(assembledStreamingResponse);
    }

    private void finishAndLog(String assembledStreamingResponse) {
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

        public ChatCompletionSseListener(List<BaseResponseFunction> functions) {
            super(functions);
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
