package com.epam.aidial.core.server.controller.route;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.request.ChatCompletionRequest;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@AllArgsConstructor
@Slf4j
class RouteRequestBodyHandler {

    private final ProxyContext context;
    private final Proxy proxy;
    private final BaseRouteController controller;

    Future<Void> handle() {
        context.getRequest().body()
                .onFailure(this::handleRequestBodyError)
                .onSuccess(body -> proxy.getTaskExecutor().submit(() -> {
                    handleRequestBody(body);
                    return null;
                }).onFailure(this::handleError));
        return Future.succeededFuture();
    }

    private void handleError(Throwable error) {
        if (error instanceof HttpException httpException) {
            controller.respond(httpException);
        } else {
            String errorMsg = "Error occurred on processing route request: %s".formatted(context.getRequest().path());
            controller.respond(HttpStatus.INTERNAL_SERVER_ERROR, errorMsg);
            log.error(errorMsg, error);
        }
    }

    private void handleRequestBody(Buffer requestBody) {
        Deployment deployment = context.getDeployment();
        log.info("Received body from client. Deployment: {}. Length: {}",
                deployment == null ? "N/A" : deployment.getName(), requestBody.length());

        context.setRequestBodyTimestamp(System.currentTimeMillis());
        context.setRequestBody(requestBody);

        ApiKeyData apiKeyData = controller.setupProxyApiKeyData();

        controller.setupEnhancementFunctions();

        if (!controller.enhancementFunctions.isEmpty()) {
            ObjectNode tree;
            try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
                tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            } catch (Exception e) {
                // request body is not JSON or malformed
                tree = ProxyUtil.MAPPER.createObjectNode();
            }
            try {
                ChatCompletionRequest request = new ChatCompletionRequest(tree);
                if (ProxyUtil.processChain(request, controller.enhancementFunctions)) {
                    context.setRequestBody(Buffer.buffer(request.serialize()));
                }
            } catch (Throwable e) {
                if (e instanceof HttpException httpException) {
                    controller.respond(httpException.getStatus(), httpException.getMessage());
                } else {
                    controller.respond(HttpStatus.BAD_REQUEST);
                }
                log.warn("Can't process JSON request body. Error:", e);
                return;
            }
        }
        if (apiKeyData != null) {
            proxy.getApiKeyStore().assignPerRequestApiKey(apiKeyData);
        }
        sendRequest();
    }

    @SneakyThrows
    private void sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        Upstream upstream = route.get();
        Objects.requireNonNull(upstream);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(getEndpointUri(upstream))
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation())
                .setConnectTimeout(context.getProxy().getClientOptions().getConnectTimeout())
                .setIdleTimeout(context.getProxy().getClientOptions().getIdleTimeout());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    @SneakyThrows
    private String getEndpointUri(Upstream upstream) {
        URIBuilder uriBuilder = new URIBuilder(upstream.getEndpoint());
        if (context.getRoute().isRewritePath()) {
            uriBuilder.setPath(controller.getRoutePath());
            String query = context.getRequest().query();
            if (query != null) {
                uriBuilder.setParameters(URLEncodedUtils.parse(query, StandardCharsets.UTF_8));
            }
        }
        return uriBuilder.toString();
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin: {}", proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        controller.copyHeaders(request.headers(), proxyRequest.headers());
        ProxyUtil.setOverrideNameHeader(proxyRequest, context.getDeployment());

        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        controller.injectAdditionalHeaders(proxyRequest);

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        int responseStatusCode = proxyResponse.statusCode();
        log.info("Received response header from origin: status={}, headers={}", responseStatusCode,
                proxyResponse.headers().size());

        if (responseStatusCode == HttpStatus.TOO_MANY_REQUESTS.getCode()) {
            UpstreamRoute upstreamRoute = context.getUpstreamRoute();
            upstreamRoute.fail(proxyResponse);
            // get next upstream
            if (controller.canRetry(upstreamRoute)) {
                sendRequest(); // try next
            }
            return;
        }

        if (responseStatusCode == 200) {
            context.getUpstreamRoute().succeed();
            String bucket = BucketBuilder.buildInitiatorBucket(context);
            proxy.getRateLimiter()
                    .increase(context.getRoute(), bucket, context.getTokenUsage(), context.getRequestBody(), context.getResponseBody())
                    .onFailure(error -> log.warn("Failed to increase limit", error));
        }

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(proxyResponseStream))
                .onFailure(this::handleResponseError);
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse(BufferingReadStream responseStream) {
        Buffer proxyResponseBody = responseStream.getContent();
        context.setResponseBody(proxyResponseBody);
        controller.handleProxyResponseBody(proxyResponseBody).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to handle proxy response", result.cause());
            }
            HttpServerResponse response = context.getResponse();
            responseStream.end(response);
            proxy.getLogStore().save(AnalyticsLogContext.from(context, null));
            controller.finalizeRequest();
        });
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        controller.respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
        log.warn("Failed to receive client body: {}", error.getMessage());
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (controller.canRetry(upstreamRoute)) {
            sendRequest(); // try next
        }
    }

    /**
     * Called when proxy failed to send request to the origin.
     */
    private void handleProxyRequestError(Throwable error) {
        log.warn("Can't send request to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (controller.canRetry(upstreamRoute)) {
            sendRequest(); // try next
        }
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        controller.finalizeRequest();
    }

}
