package com.epam.aidial.core.mcp.tools;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;

import java.util.Map;

/**
 * Adapter from the SDK's {@link McpAsyncServerExchange} to the per-call data tool handlers
 * actually need: the inbound auth headers (forwarded verbatim to Core via {@code DialClient})
 * and the MCP session-id (bucket-cache key).
 *
 * <p>The transport ({@code VertxMcpTransportProvider}) publishes the inbound {@code Api-Key}
 * and {@code Authorization} headers into the SDK's {@link McpTransportContext} under the key
 * {@link #AUTH_HEADERS_KEY}. Tool handlers read them through this adapter so the
 * transport-context contract stays in one place.
 */
public final class ToolContext {

    public static final String AUTH_HEADERS_KEY = "authHeaders";

    private ToolContext() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, String> authHeaders(McpAsyncServerExchange exchange) {
        if (exchange == null || exchange.transportContext() == null) {
            return Map.of();
        }
        Object value = exchange.transportContext().get(AUTH_HEADERS_KEY);
        if (value instanceof Map<?, ?> m) {
            return (Map<String, String>) m;
        }
        return Map.of();
    }

    public static String sessionId(McpAsyncServerExchange exchange) {
        return exchange == null ? null : exchange.sessionId();
    }
}
