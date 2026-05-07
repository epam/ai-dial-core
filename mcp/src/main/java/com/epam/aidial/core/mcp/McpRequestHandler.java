package com.epam.aidial.core.mcp;

import com.epam.aidial.core.mcp.transport.VertxMcpTransportProvider;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;

public class McpRequestHandler implements Handler<HttpServerRequest> {

    private static final long READINESS_TIMEOUT_MS = 2000;

    private final VertxMcpTransportProvider transportProvider;
    private final Vertx vertx;
    private final Future<Void> readyFuture;

    public McpRequestHandler(VertxMcpTransportProvider transportProvider, Vertx vertx, Future<Void> readyFuture) {
        this.transportProvider = transportProvider;
        this.vertx = vertx;
        this.readyFuture = readyFuture;
    }

    @Override
    public void handle(HttpServerRequest request) {
        if (readyFuture.isComplete()) {
            dispatch(request);
            return;
        }
        // Pause the body so it isn't drained before the transport installs its bodyHandler.
        request.pause();
        Context responseContext = vertx.getOrCreateContext();
        long timerId = vertx.setTimer(READINESS_TIMEOUT_MS, id -> respond503IfOpen(request));
        readyFuture.onComplete(ar -> {
            if (!vertx.cancelTimer(timerId)) {
                return;
            }
            responseContext.runOnContext(v -> {
                dispatch(request);
                request.resume();
            });
        });
    }

    private void dispatch(HttpServerRequest request) {
        if (readyFuture.succeeded()) {
            transportProvider.handleRequest(request);
        } else {
            respond503IfOpen(request);
        }
    }

    private static void respond503IfOpen(HttpServerRequest request) {
        if (!request.response().ended()) {
            request.response().setStatusCode(503).end();
        }
    }
}
