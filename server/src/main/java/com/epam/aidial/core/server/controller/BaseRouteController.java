package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.WebSocketFrameType;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public abstract class BaseRouteController implements Controller {

    private static final short CLOSE_CODE_POLICY_VIOLATION = 1008;
    private static final short CLOSE_CODE_TRY_AGAIN = 1013;
    private static final short CLOSE_CODE_INTERNAL_ERROR = 1011;

    protected final Proxy proxy;
    protected final ProxyContext context;

    protected final List<BaseRequestFunction<ObjectNode>> enhancementFunctions = new ArrayList<>();

    @Override
    public Future<?> handle() {
        return getRoutes().map(this::selectRoute).compose(this::handleRoute).otherwise(error -> {
            handleError(error);
            return null;
        });
    }

    protected Future<?> handleRoute(Route route) {
        if (route == null) {
            respond(HttpStatus.BAD_GATEWAY, "No route");
            log.warn("RouteController can't find a route to proceed the request: {}", getRequestUri());
            return Future.succeededFuture();
        }
        context.setRoute(route);

        if (!hasAccessByUserRoles(route)) {
            respond(HttpStatus.FORBIDDEN, "Forbidden route");
            log.warn("Forbidden route {}", route.getName());
            return Future.succeededFuture();
        }

        return hasRequiredPermissions(route.getPermissions()).compose(result -> {
            if (!result) {
                // the route has no required permissions
                context.respond(HttpStatus.FORBIDDEN, "Forbidden route");
                return Future.succeededFuture();
            }
            Route.Response response = route.getResponse();
            if (response == null) {
                UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider().get(route);
                if (!canRetry(upstreamRoute)) {
                    return Future.succeededFuture();
                }
                context.setTraceOperation("Send request to %s route".formatted(route.getName()));
                context.setUpstreamRoute(upstreamRoute);
            } else {
                context.getResponse().setStatusCode(response.getStatus());
                context.setResponseBody(Buffer.buffer(response.getBody()));
            }
            return proxy.getRateLimiter().limit(context, context.getRoute())
                    .compose(rateLimitResult -> {
                        Future<?> future;
                        if (rateLimitResult.status() == HttpStatus.OK) {
                            future = handleRateLimitSuccess();
                        } else {
                            handleRateLimitHit(rateLimitResult);
                            future = Future.succeededFuture();
                        }
                        return future;
                    });
        });
    }

    private Future<?> handleRateLimitSuccess() {
        if (context.getResponseBody() == null) {
            setupProxyApiKeyData();
            return proxy.getTokenStatsTracker().startSpan(context).map(ignore -> {
                if (isWebSocketUpgrade(context.getRequest())) {
                    Future<ServerWebSocket> future = context.getRequest().toWebSocket();
                    future.compose(this::handleWebSocket)
                            .onFailure(error -> {
                                if (!(error instanceof HandledException)) {
                                    log.error("WebSocket handler failed", error);
                                    finalizeRequest();
                                }
                            });

                } else {
                    handleRequestBody();
                }
                return null;
            });
        } else {
            context.getResponse().send(context.getResponseBody());
            proxy.getLogStore().save(context);
            return Future.succeededFuture();
        }
    }

    static boolean isWebSocketUpgrade(HttpServerRequest request) {
        String upgradeHeader = request.getHeader(HttpHeaders.UPGRADE);
        return "websocket".equalsIgnoreCase(upgradeHeader);
    }

    private Future<Void> handleWebSocket(ServerWebSocket serverWebSocket) {
        Promise<Void> promise = Promise.promise();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<WebSocket> upstreamRef = new AtomicReference<>();

        serverWebSocket.closeHandler(v -> handleClientClosure(closed, upstreamRef.get(), promise));
        serverWebSocket.exceptionHandler(error -> {
            log.debug("Client WebSocket error: {}", error.getMessage(), error);
            handleClientClosure(closed, upstreamRef.get(), promise);
        });

        attemptConnect(serverWebSocket, upstreamRef, closed, promise);
        return promise.future();
    }

    private void handleClientClosure(AtomicBoolean closed, WebSocket upstream, Promise<Void> promise) {
        if (closed.compareAndSet(false, true)) {
            if (upstream != null) {
                upstream.close();
            }
            finalizeRequest();
            promise.tryFail(new HandledException());
        }
    }

    private void attemptConnect(ServerWebSocket serverWebSocket, AtomicReference<WebSocket> upstreamRef,
                                AtomicBoolean closed, Promise<Void> promise) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        Upstream upstream = upstreamRoute.get();
        if (upstream == null) {
            close(serverWebSocket, HttpStatus.BAD_GATEWAY, "No upstream");
            promise.tryFail(new HandledException());
            return;
        }

        WebSocketConnectOptions options = buildConnectOptions(context, upstream);
        proxy.getClient().webSocket(options).onSuccess(upstreamWebSocket -> {
            if (closed.get()) {
                upstreamWebSocket.close();
                promise.tryFail(new HandledException());
                return;
            }
            handleConnectedWebSocket(serverWebSocket, upstreamWebSocket, upstreamRef, closed);
            promise.tryComplete();
        }).onFailure(error -> {
            log.warn("Failed to connect WebSocket to upstream: {}", error.getMessage());
            upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
            try {
                upstreamRoute.next();
                attemptConnect(serverWebSocket, upstreamRef, closed, promise);
            } catch (HttpException e) {
                close(serverWebSocket, e.getStatus(), e.getMessage());
                promise.tryFail(new HandledException());
            }
        });
    }

    private void close(ServerWebSocket clientSocket, HttpStatus status, String reason) {
        if (clientSocket == null || clientSocket.isClosed()) {
            return;
        }
        short code = mapStatusToCloseCode(status);
        String message = reason == null ? status.toString() : reason;
        clientSocket.close(code, message);
    }

    private short mapStatusToCloseCode(HttpStatus status) {
        return switch (status) {
            case TOO_MANY_REQUESTS -> CLOSE_CODE_TRY_AGAIN;
            case FORBIDDEN, UNAUTHORIZED -> CLOSE_CODE_POLICY_VIOLATION;
            default -> CLOSE_CODE_INTERNAL_ERROR;
        };
    }

    private WebSocketConnectOptions buildConnectOptions(ProxyContext context, Upstream upstream) {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setAbsoluteURI(resolveEndpointUri(context, upstream))
                .setTimeout(proxy.getClientOptions().getConnectTimeout());

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ProxyUtil.copyHeaders(context.getRequest().headers(), headers);
        if (upstream.getKey() != null) {
            headers.set(Proxy.HEADER_API_KEY, upstream.getKey());
        } else {
            ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
            headers.set(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());
        }

        options.setHeaders(headers);
        return options;
    }

    private void handleConnectedWebSocket(ServerWebSocket serverWebSocket, WebSocket upstreamSocket,
                                          AtomicReference<WebSocket> upstreamRef, AtomicBoolean closed) {
        log.info("WebSocket connected to upstream {}", upstreamSocket.remoteAddress());

        upstreamRef.set(upstreamSocket);
        context.getUpstreamRoute().succeed();

        upstreamSocket.closeHandler(v -> {
            if (closed.compareAndSet(false, true)) {
                serverWebSocket.close();
                finalizeRequest();
            }
        });

        upstreamSocket.exceptionHandler(error -> {
            log.debug("Upstream WebSocket exception: {}", error.getMessage(), error);
            if (closed.compareAndSet(false, true)) {
                serverWebSocket.close();
                finalizeRequest();
            }
        });

        forwardFrames(serverWebSocket, upstreamSocket);
        forwardFrames(upstreamSocket, serverWebSocket);
    }

    private void forwardFrames(WebSocketBase source, WebSocketBase target) {
        source.frameHandler(frame -> {
            if (target.isClosed()) {
                closeQuietly(source);
                return;
            }

            try {
                if (target.writeQueueFull()) {
                    source.pause();
                    target.drainHandler(v -> {
                        target.drainHandler(null);
                        source.resume();
                    });
                }

                WebSocketFrameType type = frame.type();
                switch (type) {
                    case TEXT -> target.writeFrame(WebSocketFrame.textFrame(frame.textData(), frame.isFinal()));
                    case BINARY -> target.writeFrame(WebSocketFrame.binaryFrame(frame.binaryData().copy(), frame.isFinal()));
                    case CONTINUATION -> target.writeFrame(WebSocketFrame.continuationFrame(frame.binaryData().copy(), frame.isFinal()));
                    case PING -> target.writePing(frame.binaryData().copy());
                    case PONG -> target.writePong(frame.binaryData().copy());
                    case CLOSE -> target.close(frame.closeStatusCode(), frame.closeReason());
                    default -> target.writeFrame(frame);
                }
            } catch (IllegalStateException e) {
                log.debug("WebSocket target closed while forwarding frame: {}", e.getMessage());
                closeQuietly(source);
            }
        });
    }

    private void closeQuietly(WebSocketBase socket) {
        if (socket == null || socket.isClosed()) {
            return;
        }
        try {
            socket.close();
        } catch (IllegalStateException e) {
            log.debug("WebSocket already closed: {}", e.getMessage());
        }
    }

    @SneakyThrows
    private String resolveEndpointUri(ProxyContext context, Upstream upstream) {
        URIBuilder builder = new URIBuilder(upstream.getEndpoint());
        if (context.getRoute().isRewritePath()) {
            builder.setPath(context.getRequest().path());
            String query = context.getRequest().query();
            if (query != null) {
                builder.setParameters(URLEncodedUtils.parse(query, StandardCharsets.UTF_8));
            }
        }
        return builder.toString();
    }

    protected abstract Future<Boolean> hasRequiredPermissions(Set<ResourceAccessType> permissions);

    protected boolean hasAccessByUserRoles(Route route) {
        return route.hasAccess(context.getUserRoles());
    }

    String getRequestUri() {
        HttpServerRequest request = context.getRequest();
        return request.uri();
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

    private void handleRequestBody() {
        context.getRequest().body()
                .onFailure(this::handleRequestBodyError)
                .onSuccess(body -> proxy.getTaskExecutor().submit(() -> {
                    handleRequestBody(body);
                    return null;
                }));
    }

    private void handleRequestBody(Buffer requestBody) {
        Deployment deployment = context.getDeployment();
        log.info("Received body from client. Deployment: {}. Length: {}",
                deployment == null ? "N/A" : deployment.getName(), requestBody.length());

        context.setRequestBodyTimestamp(System.currentTimeMillis());
        context.setRequestBody(requestBody);

        setupEnhancementFunctions();

        if (!enhancementFunctions.isEmpty()) {
            ObjectNode tree;
            try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
                tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            } catch (Exception e) {
                // request body is not JSON or malformed
                tree = ProxyUtil.MAPPER.createObjectNode();
            }
            try {
                if (ProxyUtil.processChain(tree, enhancementFunctions)) {
                    context.setRequestBody(Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)));
                }
            } catch (Throwable e) {
                if (e instanceof HttpException httpException) {
                    respond(httpException.getStatus(), httpException.getMessage());
                } else {
                    respond(HttpStatus.BAD_REQUEST);
                }
                log.warn("Can't process JSON request body. Error:", e);
                return;
            }
        }

        proxy.getApiKeyStore().assignPerRequestApiKey(context.getProxyApiKeyData());

        sendRequest();
    }

    protected void setupEnhancementFunctions() {
        // do nothing by default
    }

    private void setupProxyApiKeyData() {
        Upstream upstream = context.getUpstreamRoute().get();
        if (upstream != null && upstream.getKey() != null) {
            return;
        }
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin: {}", proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        Upstream upstream = context.getUpstreamRoute().get();
        MultiMap excludeHeaders = excludeHeaders();
        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);
        if (upstream != null && upstream.getKey() != null) {
            proxyRequest.putHeader(Proxy.HEADER_API_KEY, upstream.getKey());
        } else {
            ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
            proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());
        }

        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        injectAdditionalHeaders(proxyRequest);

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    protected MultiMap excludeHeaders() {
        return MultiMap.caseInsensitiveMultiMap();
    }

    protected abstract void injectAdditionalHeaders(HttpClientRequest proxyRequest);

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
            if (canRetry(upstreamRoute)) {
                sendRequest(); // try next
            }
            return;
        }

        if (responseStatusCode == 200) {
            context.getUpstreamRoute().succeed();
            proxy.getRateLimiter().increase(context, context.getRoute()).onFailure(error -> log.warn("Failed to increase limit", error));
        }

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setResponseStream(proxyResponseStream);

        HttpServerResponse response = context.getResponse();
        response.setChunked(true);
        response.setStatusCode(responseStatusCode);
        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleResponse())
                .onFailure(this::handleResponseError);
    }

    private boolean canRetry(UpstreamRoute route) {
        try {
            route.next();
        } catch (HttpException e) {
            respond(e);
            return false;
        }
        return true;
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse() {
        BufferingReadStream responseStream = context.getResponseStream();
        Buffer proxyResponseBody = responseStream.getContent();
        context.setResponseBody(proxyResponseBody);
        handleProxyResponseBody(proxyResponseBody).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to handle proxy response", result.cause());
            }
            HttpServerResponse response = context.getResponse();
            responseStream.end(response);
            proxy.getLogStore().save(context);
            finalizeRequest();
        });
    }

    protected abstract Future<Void> handleProxyResponseBody(Buffer responseBody);

    private void handleRateLimitHit(RateLimitResult result) {
        ErrorData rateLimitError = new ErrorData();
        rateLimitError.getError().setCode(String.valueOf(result.status().getCode()));
        rateLimitError.getError().setMessage(result.errorMessage());
        rateLimitError.getError().setDisplayMessage(result.displayErrorMessage());

        String errorMessage = ProxyUtil.convertToString(rateLimitError);
        HttpException httpException;
        if (result.replyAfterSeconds() >= 0) {
            Map<String, String> headers = Map.of(HttpHeaders.RETRY_AFTER.toString(), Long.toString(result.replyAfterSeconds()));
            httpException = new HttpException(result.status(), errorMessage, headers);
        } else {
            httpException = new HttpException(result.status(), errorMessage);
        }

        respond(httpException);
        
        log.warn("Rate limit error {}. Route: {}", result.errorMessage(), context.getRoute().getName());
    }

    private void handleError(Throwable error) {
        if (error instanceof HttpException httpException) {
            respond(httpException);
        } else {
            String errorMsg = "Error occurred on processing route request: %s".formatted(context.getRequest().path());
            respond(HttpStatus.INTERNAL_SERVER_ERROR, errorMsg);
            log.error(errorMsg, error);
        }
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
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
        if (canRetry(upstreamRoute)) {
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
        if (canRetry(upstreamRoute)) {
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
        finalizeRequest();
    }

    protected abstract Future<Collection<Route>> getRoutes();

    private Route selectRoute(Collection<Route> routes) {
        HttpServerRequest request = context.getRequest();
        String path = getRoutePath();

        for (Route route : routes) {
            List<Pattern> paths = route.getPaths();
            Set<String> methods = route.getMethods();

            if (!methods.isEmpty() && !methods.contains(request.method().name())) {
                continue;
            }

            if (paths.isEmpty()) {
                return route;
            }

            for (Pattern pattern : route.getPaths()) {
                if (pattern.matcher(path).matches()) {
                    return route;
                }
            }
        }

        return null;
    }

    @SneakyThrows
    private String getEndpointUri(Upstream upstream) {
        URIBuilder uriBuilder = new URIBuilder(upstream.getEndpoint());
        if (context.getRoute().isRewritePath()) {
            uriBuilder.setPath(getRoutePath());
            String query = context.getRequest().query();
            if (query != null) {
                uriBuilder.setParameters(URLEncodedUtils.parse(query, StandardCharsets.UTF_8));
            }
        }
        return uriBuilder.toString();
    }

    protected abstract String getRoutePath();

    private void respond(HttpStatus status, String result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    private void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    private void finalizeRequest() {
        proxy.getTokenStatsTracker().endSpan(context).onFailure(error -> log.error("Error occurred at completing span", error));
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

    static class HandledException extends RuntimeException {
        HandledException() {
            super("Handled WebSocket proxy exception", null, false, false);
        }
    }
}