package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

@Slf4j
public abstract class BaseInterceptorController extends BaseDeploymentPostController {

    private final List<BaseRequestFunction<RequestObject>> enhancementFunctions;
    private final int interceptorIndex;

    protected BaseInterceptorController(Proxy proxy, ProxyContext context, int interceptorIndex,
            List<BaseRequestFunction<RequestObject>> enhancementFunctions) {
        super(proxy, context);
        this.enhancementFunctions = enhancementFunctions;
        this.interceptorIndex = interceptorIndex;
    }

    protected abstract RequestObject parseRequest(Buffer body) throws IOException;

    protected abstract String buildUri(ProxyContext context);

    protected abstract CollectResponseAttachmentsFn createAttachmentFn(Proxy proxy, ProxyContext context);

    protected abstract BufferingReadStream.BaseEventListener createListener(Proxy proxy, ProxyContext context);

    public Future<?> handle() {
        List<String> interceptors = context.getInterceptors();
        String interceptorName = interceptors.get(interceptorIndex);
        Interceptor interceptor = context.getConfig().getInterceptors().get(interceptorName);
        if (interceptor == null) {
            log.warn("Interceptor is not found: {}", interceptorName);
            return respond(HttpStatus.NOT_FOUND, "Interceptor is not found");
        }
        context.setTraceOperation("Send request to %s interceptor".formatted(interceptorName));
        context.setDeployment(interceptor);
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        proxyApiKeyData.setInterceptorIndex(interceptorIndex);
        proxyApiKeyData.setInterceptors(interceptors);
        proxyApiKeyData.setInitialDeployment(context.getInitialDeployment());
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);

        log.info("Received request from client. Deployment: {}. Headers: {}",
                context.getDeployment().getName(),
                context.getRequest().headers().size());

        return proxy.getTokenStatsTracker().startSpan(context).map(ignore -> {
            context.getRequest().body()
                    .onSuccess(body -> proxy.getTaskExecutor().submit(() -> {
                        handleRequestBody(body);
                        return null;
                    }).onFailure(this::handleError))
                    .onFailure(this::handleRequestBodyError);
            return null;
        });
    }

    private void handleError(Throwable error) {
        log.error("Can't handle request. Error: {}", error.getMessage());
        respond(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @VisibleForTesting
    void handleRequestBody(Buffer requestBody) {
        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());
        try {
            RequestObject request = parseRequest(requestBody);
            if (request != null) {
                context.setStreamingRequest(request.isStreaming());
                if (ProxyUtil.processChain(request, enhancementFunctions)) {
                    context.setRequestBody(Buffer.buffer(request.serialize()));
                }
            }
            proxy.getApiKeyStore().assignPerRequestApiKey(context.getProxyApiKeyData());
        } catch (Throwable e) {
            if (e instanceof HttpException httpException) {
                respond(httpException.getStatus(), httpException.getMessage());
            } else {
                respond(HttpStatus.BAD_REQUEST);
            }
            log.warn("Can't process JSON request body.  Error:", e);
            return;
        }
        sendRequest();
    }

    private void sendRequest() {
        createProxyRequest(buildUri(context))
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to interceptor. Deployment: {}. Address: {}",
                context.getDeployment().getName(), proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());

        proxyRequest.putHeader(Proxy.HEADER_DEPLOYMENT_ID, context.getInitialDeployment());

        enrichProxyRequestHeaders(proxyRequest);

        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * Called when proxy failed to receive response header from origin.
     */
    private void handleProxyResponseError(Throwable error) {
        log.warn("Proxy failed to receive response header from origin. Address: {}. Error:",
                context.getProxyRequest().connection().remoteAddress(),
                error);
    }

    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received header from origin. Endpoint: {}. Status: {}. Headers: {}",
                context.getProxyRequestUri(), proxyResponse.statusCode(), proxyResponse.headers().size());

        BufferingReadStream responseStream = createResponseStream(proxyResponse, () -> createListener(proxy, context));

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignore -> handleResponse(responseStream))
                .onFailure(this::handleResponseError);
    }

    private void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = responseStream.getContent();
        collectResponseAttachments(responseBody, createAttachmentFn(proxy, context)).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect attachments from response. Error:", result.cause());
            }
            completeProxyResponse(responseStream);
        });
    }

    private void completeProxyResponse(BufferingReadStream responseStream) {
        HttpServerResponse response = context.getResponse();
        responseStream.end(response);
        finalizeRequest();
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client. Error:", error);

        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }
}
