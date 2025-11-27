package com.epam.aidial.core.server.controller.route;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketBase;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.http.WebSocketFrame;
import io.vertx.core.http.WebSocketFrameType;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

@AllArgsConstructor
@Slf4j
class RouteWebSocketHandler {

    private static final short CLOSE_CODE_POLICY_VIOLATION = 1008;
    private static final short CLOSE_CODE_TRY_AGAIN = 1013;
    private static final short CLOSE_CODE_INTERNAL_ERROR = 1011;

    private final ProxyContext context;
    private final Proxy proxy;
    private final BaseRouteController controller;

    public Future<Void> handle() {
        Future<ServerWebSocket> future = context.getRequest().toWebSocket();
        return future.compose(this::handleWebSocket)
                .onFailure(this::handleWebSocketFailure);
    }

    private void handleWebSocketFailure(Throwable error) {
        if (!(error instanceof HandledException)) {
            log.error("WebSocket handler failed", error);
            controller.finalizeRequest();
            close(context.getServerWebSocket(), HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error occurred on handling web socket");
        }
    }

    private Future<Void> handleWebSocket(ServerWebSocket serverWebSocket) {
        serverWebSocket.pause();
        Promise<Void> promise = Promise.promise();
        promise.future().onComplete(result -> serverWebSocket.resume());
        context.setServerWebSocket(serverWebSocket);

        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<WebSocket> upstreamRef = new AtomicReference<>();

        serverWebSocket.closeHandler(v -> handleWebSocketClosure(closed, upstreamRef.get()));
        serverWebSocket.exceptionHandler(error -> {
            log.debug("Client WebSocket error: {}", error.getMessage(), error);
            handleWebSocketClosure(closed, upstreamRef.get());
        });

        attemptConnect(serverWebSocket, upstreamRef, closed, promise);
        return promise.future();
    }

    private void handleWebSocketClosure(AtomicBoolean closed, WebSocketBase webSocket) {
        if (closed.compareAndSet(false, true)) {
            if (webSocket != null) {
                webSocket.close();
            }
            controller.finalizeRequest();
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
        proxy.getWebSocketClient().connect(options).onSuccess(upstreamWebSocket -> {
            if (closed.get()) {
                upstreamWebSocket.close();
                promise.tryFail(new HandledException());
                return;
            }
            handleUpstreamWebSocket(serverWebSocket, upstreamWebSocket, upstreamRef, closed);
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

    private static void close(ServerWebSocket serverWebSocket, HttpStatus status, String reason) {
        if (serverWebSocket == null || serverWebSocket.isClosed()) {
            return;
        }
        short code = mapStatusToCloseCode(status);
        String message = reason == null ? status.toString() : reason;
        serverWebSocket.close(code, message);
    }

    private static short mapStatusToCloseCode(HttpStatus status) {
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

        MultiMap proxyHeaders = MultiMap.caseInsensitiveMultiMap();
        controller.copyHeaders(context.getRequest().headers(), proxyHeaders);
        ProxyUtil.setOverrideNameHeader(proxyHeaders, context.getDeployment());

        options.setHeaders(proxyHeaders);
        return options;
    }

    private void handleUpstreamWebSocket(ServerWebSocket serverWebSocket, WebSocket upstreamSocket,
                                         AtomicReference<WebSocket> upstreamRef, AtomicBoolean closed) {
        log.info("WebSocket connected to upstream {}", upstreamSocket.remoteAddress());

        upstreamRef.set(upstreamSocket);
        context.getUpstreamRoute().succeed();

        upstreamSocket.closeHandler(v -> handleWebSocketClosure(closed, serverWebSocket));

        upstreamSocket.exceptionHandler(error -> {
            log.debug("Upstream WebSocket exception: {}", error.getMessage(), error);
            handleWebSocketClosure(closed, serverWebSocket);
        });

        forwardFrames(serverWebSocket, upstreamSocket);
        forwardFrames(upstreamSocket, serverWebSocket);
    }

    private static void forwardFrames(WebSocketBase source, WebSocketBase target) {
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

    private static void closeQuietly(WebSocketBase socket) {
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

    private static class HandledException extends RuntimeException {
        HandledException() {
            super("Handled WebSocket proxy exception", null, false, false);
        }
    }
}
