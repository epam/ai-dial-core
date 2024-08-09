package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.function.BaseFunction;
import com.epam.aidial.core.function.CollectAttachmentsFn;
import com.epam.aidial.core.util.BufferingReadStream;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@Slf4j
public class InterceptorController {

    private final Proxy proxy;
    private final ProxyContext context;

    private final List<BaseFunction<ObjectNode>> enhancementFunctions;

    public InterceptorController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.enhancementFunctions = List.of(new CollectAttachmentsFn(proxy, context));
    }

    public Future<?> handle() {
        log.info("Received request from client. Trace: {}. Span: {}. Key: {}. Deployment: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getRequest().headers().size());
        proxy.getTokenStatsTracker().startSpan(context);
        context.getRequest().body()
                .onSuccess(body -> proxy.getVertx().executeBlocking(() -> {
                    handleRequestBody(body);
                    return null;
                }, false).onFailure(this::handleError))
                .onFailure(this::handleRequestBodyError);
        return Future.succeededFuture();
    }

    private void handleError(Throwable error) {
        log.error("Can't handle request. Key: {}. User sub: {}. Trace: {}. Span: {}. Error: {}",
                context.getProject(), context.getUserSub(), context.getTraceId(), context.getSpanId(), error.getMessage());
        respond(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());
        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            Throwable error = ProxyUtil.processChain(tree, enhancementFunctions);
            if (error != null) {
                finalizeRequest();
                return;
            }
        } catch (IOException e) {
            respond(HttpStatus.BAD_REQUEST);
            log.warn("Can't parse JSON request body. Trace: {}. Span: {}. Error:",
                    context.getTraceId(), context.getSpanId(), e);
            return;
        }
        sendRequest();
    }


    private static String buildUri(ProxyContext context) {
        HttpServerRequest request = context.getRequest();
        Deployment deployment = context.getDeployment();
        String endpoint = deployment.getEndpoint();
        String query = request.query();
        return endpoint + (query == null ? "" : "?" + query);
    }

    private void sendRequest() {
        String uri = buildUri(context);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(context.getRequest().method());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBodyError(Throwable error) {
        log.warn("Failed to receive client body. Trace: {}. Span: {}. Error: {}",
                context.getTraceId(), context.getSpanId(), error.getMessage());

        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin. Trace: {}. Span: {}. Key: {}. Deployment: {}. Address: {}. Error: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(), error.getMessage());

        respond(HttpStatus.BAD_GATEWAY, "Failed to connect to origin");
    }


    void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to interceptor. Trace: {}. Span: {}. Key: {}. Deployment: {}. Address: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!context.getDeployment().isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());


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
        log.warn("Proxy failed to receive response header from origin. Trace: {}. Span: {}. Key: {}. Deployment: {}. Address: {}. Error:",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getProxyRequest().connection().remoteAddress(),
                error);
    }

    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received header from origin. Trace: {}. Span: {}. Key: {}. Deployment: {}. Endpoint: {}. Status: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size());

        BufferingReadStream responseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());
        context.setResponseStream(responseStream);

        HttpServerResponse response = context.getResponse();

        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());

        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        responseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignore -> finalizeRequest())
                .onFailure(this::handleResponseError);
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client. Trace: {}. Span: {}. Error:",
                context.getTraceId(), context.getSpanId(), error);

        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

    private void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    private void respond(HttpStatus status, Object result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void finalizeRequest() {
        proxy.getTokenStatsTracker().endSpan(context);
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(proxyApiKeyData)
                    .onSuccess(invalidated -> {
                        if (!invalidated) {
                            log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                        }
                    }).onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
    }

}
