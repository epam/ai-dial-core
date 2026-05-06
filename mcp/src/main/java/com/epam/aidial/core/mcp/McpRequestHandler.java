package com.epam.aidial.core.mcp;

import com.epam.aidial.core.mcp.transport.VertxMcpTransportProvider;
import io.vertx.core.Handler;
import io.vertx.core.http.HttpServerRequest;

public class McpRequestHandler implements Handler<HttpServerRequest> {

    private final VertxMcpTransportProvider transportProvider;

    public McpRequestHandler(VertxMcpTransportProvider transportProvider) {
        this.transportProvider = transportProvider;
    }

    @Override
    public void handle(HttpServerRequest request) {
        transportProvider.handleRequest(request);
    }
}
