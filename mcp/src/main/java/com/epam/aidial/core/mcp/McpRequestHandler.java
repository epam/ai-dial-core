package com.epam.aidial.core.mcp;

import io.vertx.core.Handler;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;

/**
 * Dispatch entry for the {@code /mcp} mount point. Returns {@code 503} until slice
 * {@code M.0.0-bridge} wires the Vert.x &harr; MCP-SDK transport adapter.
 */
public class McpRequestHandler implements Handler<HttpServerRequest> {

    static final String STUB_BODY =
            "{\"error\":\"mcp_transport_not_wired\","
                    + "\"message\":\"MCP transport adapter not yet implemented (M.0.0-bridge)\"}";

    @Override
    public void handle(HttpServerRequest request) {
        HttpServerResponse response = request.response();
        response.setStatusCode(503);
        response.putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        response.end(STUB_BODY);
    }
}
