package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpVersion;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.WebSocketFrameType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public class WebSocketProxy {

    private static final short CLOSE_CODE_POLICY_VIOLATION = 1008;
    private static final short CLOSE_CODE_TRY_AGAIN = 1013;
    private static final short CLOSE_CODE_INTERNAL_ERROR = 1011;
    private static final int MAX_CLOSE_REASON_LENGTH = 120;

    private final Proxy proxy;

    public Future<Void> handle(HttpServerRequest request, Config config, Proxy.AuthorizationResult result,
                               String traceId, String spanId, String traceFlags) {
        ProxyContext context = proxy.createContext(config, request, result, traceId, spanId, traceFlags);

        if (!isWebSocketUpgrade(request)) {
            return Future.failedFuture(new IllegalStateException("Request is not a WebSocket upgrade"));
        }

        if (request.version() != HttpVersion.HTTP_1_1) {
            return failHttp(context, HttpStatus.HTTP_VERSION_NOT_SUPPORTED, "HTTP/1.1 is required for WebSocket connections");
        }

        if (request.method() != HttpMethod.GET) {
            return failHttp(context, HttpStatus.METHOD_NOT_ALLOWED, "WebSocket upgrade must use GET");
        }

        Route route = selectRoute(config.getRoutes().values(), request);
        if (route == null) {
            log.warn("WebSocket route is not found for path {}", request.uri());
            return failHttp(context, HttpStatus.BAD_GATEWAY, "No route");
        }

        context.setRoute(route);

        if (!route.hasAccess(context.getUserRoles())) {
            log.warn("Forbidden WebSocket route {}", route.getName());
            return failHttp(context, HttpStatus.FORBIDDEN, "Forbidden route");
        }

        if (route.getResponse() != null) {
            log.warn("WebSocket route {} has static response configured - unsupported", route.getName());
            return failHttp(context, HttpStatus.BAD_GATEWAY, "WebSocket route does not support pre-defined responses");
        }

        context.setTraceOperation("Open WebSocket route %s".formatted(route.getName()));

        UpstreamRoute upstreamRoute;
        try {
            upstreamRoute = proxy.getUpstreamRouteProvider().get(route);
        } catch (Exception e) {
            log.warn("Failed to obtain upstream route for {}", route.getName(), e);
            return failHttp(context, HttpStatus.BAD_GATEWAY, "No upstream");
        }
        context.setUpstreamRoute(upstreamRoute);

        try {
            upstreamRoute.next();
        } catch (HttpException e) {
            log.warn("Upstream is unavailable: {}", e.getMessage());
            return failHttp(context, e.getStatus(), e.getMessage());
        }

        return proxy.getRateLimiter().limit(context, route)
                .compose(limitResult -> handleRateLimit(limitResult, context))
                .compose(v -> setupProxyApiKeyIfNeeded(context))
                .compose(v -> request.toWebSocket())
                .compose(clientSocket -> proxyToUpstream(clientSocket, context))
                .onFailure(error -> {
                    if (!(error instanceof HandledException)) {
                        log.warn("WebSocket proxy failed", error);
                        finalizeContext(context);
                    }
                })
                .mapEmpty();
    }

    private boolean isWebSocketUpgrade(HttpServerRequest request) {
        String upgradeHeader = request.getHeader(HttpHeaders.UPGRADE);
        return "websocket".equalsIgnoreCase(upgradeHeader);
    }

    private Future<Void> handleRateLimit(RateLimitResult result, ProxyContext context) {
        if (result.status() == HttpStatus.OK) {
            return Future.succeededFuture();
        }
        log.warn("WebSocket rate limit hit for route {}: {}", context.getRoute().getName(), result.errorMessage());
        return failHttp(context, result.status(), result.displayErrorMessage());
    }

    private Future<Void> proxyToUpstream(ServerWebSocket clientSocket, ProxyContext context) {
        Promise<Void> promise = Promise.promise();
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<WebSocket> upstreamRef = new AtomicReference<>();

        clientSocket.closeHandler(v -> handleClientClosure(closed, upstreamRef.get(), context, promise));
        clientSocket.exceptionHandler(error -> {
            log.debug("Client WebSocket error: {}", error.getMessage(), error);
            handleClientClosure(closed, upstreamRef.get(), context, promise);
        });

        attemptConnect(clientSocket, context, upstreamRef, closed, promise);
        return promise.future();
    }

    private void attemptConnect(ServerWebSocket clientSocket, ProxyContext context,
                                AtomicReference<WebSocket> upstreamRef, AtomicBoolean closed,
                                Promise<Void> promise) {
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        Upstream upstream = upstreamRoute.get();
        if (upstream == null) {
            close(clientSocket, HttpStatus.BAD_GATEWAY, "No upstream");
            promise.tryFail(new HandledException());
            return;
        }

        WebSocketConnectOptions options = buildConnectOptions(context, upstream, upstreamRoute);
        proxy.getClient().webSocket(options).onComplete(ar -> {
            if (ar.succeeded()) {
                if (closed.get()) {
                    ar.result().close();
                    promise.tryFail(new HandledException());
                    return;
                }
                handleConnectedWebSocket(clientSocket, ar.result(), context, upstreamRef, closed);
                promise.tryComplete();
            } else {
                log.warn("Failed to connect WebSocket to upstream: {}", ar.cause().getMessage());
                upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
                try {
                    upstreamRoute.next();
                    attemptConnect(clientSocket, context, upstreamRef, closed, promise);
                } catch (HttpException e) {
                    close(clientSocket, e.getStatus(), e.getMessage());
                    promise.tryFail(new HandledException());
                }
            }
        });
    }

    private WebSocketConnectOptions buildConnectOptions(ProxyContext context, Upstream upstream, UpstreamRoute upstreamRoute) {
        WebSocketConnectOptions options = new WebSocketConnectOptions()
                .setAbsoluteURI(resolveEndpointUri(context, upstream))
                .setTimeout(proxy.getClientOptions().getConnectTimeout());

        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        ProxyUtil.copyHeaders(context.getRequest().headers(), headers);
        if (upstream.getKey() != null) {
            headers.set(Proxy.HEADER_API_KEY, upstream.getKey());
        } else if (context.getProxyApiKeyData() != null) {
            headers.set(Proxy.HEADER_API_KEY, context.getProxyApiKeyData().getPerRequestKey());
        }

        if (context.getUserDisplayName() != null) {
            headers.set(Proxy.HEADER_JOB_TITLE, context.getUserDisplayName());
        }
        headers.set(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(upstreamRoute.getAttemptCount()));

        String extraData = upstream.getExtraData();
        if (extraData != null) {
            headers.set(Proxy.HEADER_UPSTREAM_EXTRA_DATA, extraData);
        }
        headers.set(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getEndpoint());

        options.setHeaders(headers);
        return options;
    }

    private void handleConnectedWebSocket(ServerWebSocket clientSocket, WebSocket upstreamSocket,
                                          ProxyContext context, AtomicReference<WebSocket> upstreamRef,
                                          AtomicBoolean closed) {
        log.info("WebSocket connected to upstream {}", upstreamSocket.remoteAddress());

        upstreamRef.set(upstreamSocket);

        context.getUpstreamRoute().succeed();
        proxy.getRateLimiter().increase(context, context.getRoute())
                .onFailure(error -> log.warn("Failed to increase rate limit", error));

        upstreamSocket.closeHandler(v -> {
            if (closed.compareAndSet(false, true)) {
                clientSocket.close();
                finalizeContext(context);
            }
        });
        upstreamSocket.exceptionHandler(error -> {
            log.debug("Upstream WebSocket exception: {}", error.getMessage(), error);
            if (closed.compareAndSet(false, true)) {
                clientSocket.close();
                finalizeContext(context);
            }
        });

        forwardFrames(clientSocket, upstreamSocket);
        forwardFrames(upstreamSocket, clientSocket);
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

    private Future<Void> setupProxyApiKeyIfNeeded(ProxyContext context) {
        Upstream upstream = context.getUpstreamRoute().get();
        if (upstream != null && upstream.getKey() != null) {
            return Future.succeededFuture();
        }

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData == null) {
            proxyApiKeyData = new ApiKeyData();
            context.setProxyApiKeyData(proxyApiKeyData);
            ApiKeyData.initFromContext(proxyApiKeyData, context);
        }

        ApiKeyData finalProxyApiKeyData = proxyApiKeyData;
        return proxy.getTaskExecutor().submit(() -> {
            proxy.getApiKeyStore().assignPerRequestApiKey(finalProxyApiKeyData);
            return null;
        }).mapEmpty();
    }

    private String resolveEndpointUri(ProxyContext context, Upstream upstream) {
        try {
            URIBuilder builder = new URIBuilder(upstream.getEndpoint());
            if (context.getRoute().isRewritePath()) {
                builder.setPath(context.getRequest().path());
                String query = context.getRequest().query();
                if (query != null) {
                    builder.setParameters(URLEncodedUtils.parse(query, StandardCharsets.UTF_8));
                }
            }
            return builder.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to build upstream URI", e);
        }
    }

    private Route selectRoute(Collection<Route> routes, HttpServerRequest request) {
        String path = request.path();
        HttpMethod method = request.method();

        for (Route route : routes) {
            Set<String> methods = route.getMethods();
            if (!methods.isEmpty() && (method == null || !methods.contains(method.name()))) {
                continue;
            }

            List<Pattern> paths = route.getPaths();
            if (paths.isEmpty()) {
                return route;
            }

            for (Pattern pattern : paths) {
                if (pattern.matcher(path).matches()) {
                    return route;
                }
            }
        }

        return null;
    }

    private Future<Void> failHttp(ProxyContext context, HttpStatus status, String message) {
        return context.respond(status, message)
                .onComplete(ar -> finalizeContext(context))
                .compose(v -> Future.<Void>failedFuture(new HandledException()));
    }

    private void handleClientClosure(AtomicBoolean closed, WebSocket upstream, ProxyContext context, Promise<Void> promise) {
        if (closed.compareAndSet(false, true)) {
            if (upstream != null) {
                upstream.close();
            }
            finalizeContext(context);
            promise.tryFail(new HandledException());
        }
    }

    private void close(ServerWebSocket clientSocket, HttpStatus status, String reason) {
        if (clientSocket == null || clientSocket.isClosed()) {
            return;
        }
        short code = mapStatusToCloseCode(status);
        String message = reason == null ? status.toString() : reason;
        if (message.length() > MAX_CLOSE_REASON_LENGTH) {
            message = message.substring(0, MAX_CLOSE_REASON_LENGTH);
        }
        clientSocket.close(code, message);
    }

    private short mapStatusToCloseCode(HttpStatus status) {
        return switch (status) {
            case TOO_MANY_REQUESTS -> CLOSE_CODE_TRY_AGAIN;
            case FORBIDDEN, UNAUTHORIZED -> CLOSE_CODE_POLICY_VIOLATION;
            default -> CLOSE_CODE_INTERNAL_ERROR;
        };
    }

    private void finalizeContext(ProxyContext context) {
        ContextManager.clearContext();
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(proxyApiKeyData)
                    .onFailure(error -> log.error("Failed to invalidate per-request API key", error));
        }
    }

    static class HandledException extends RuntimeException {
        HandledException() {
            super("Handled WebSocket proxy exception", null, false, false);
        }
    }
}
