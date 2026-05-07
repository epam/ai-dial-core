package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Package-private helpers for MCP HTTP/SSE handshake + tool invocation reused by
 * {@link McpReadToolsTest} and {@link McpWriteToolsTest}. Stateless: each method takes the
 * caller's {@link ResourceBaseTest} for {@code send(...)} access.
 */
final class McpTestSupport {

    static final String JSON = "application/json";
    static final String SSE = "text/event-stream";
    static final String ACCEPT_BOTH = JSON + ", " + SSE;
    static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private McpTestSupport() {
    }

    static String handshake(ResourceBaseTest test) throws Exception {
        ResourceBaseTest.Response init = sendInitialize(test);
        assertEquals(200, init.status());
        String sessionId = init.headers().get(SESSION_HEADER);
        assertNotNull(sessionId);
        sendInitialized(test, sessionId);
        return sessionId;
    }

    static JsonNode callTool(ResourceBaseTest test, String sessionId, String name,
                             ObjectNode arguments, String authorization) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 99);
        envelope.put("method", "tools/call");
        envelope.set("params", params);
        return callMcp(test, sessionId, MAPPER.writeValueAsString(envelope), authorization).get("result");
    }

    static JsonNode callMcp(ResourceBaseTest test, String sessionId, String envelope, String authorization) throws Exception {
        ResourceBaseTest.Response response;
        if (authorization == null) {
            response = test.send(HttpMethod.POST, "/mcp", null, envelope,
                    "Content-Type", JSON,
                    "Accept", ACCEPT_BOTH,
                    SESSION_HEADER, sessionId);
        } else {
            response = test.send(HttpMethod.POST, "/mcp", null, envelope,
                    "Content-Type", JSON,
                    "Accept", ACCEPT_BOTH,
                    "Authorization", authorization,
                    SESSION_HEADER, sessionId);
        }
        assertEquals(200, response.status());
        return parseSseOrJson(response.body());
    }

    static String toolsListEnvelope() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
    }

    static ResourceBaseTest.Response sendInitialize(ResourceBaseTest test) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("protocolVersion", "2025-03-26");
        params.set("capabilities", MAPPER.createObjectNode());
        ObjectNode clientInfo = MAPPER.createObjectNode();
        clientInfo.put("name", "dial-mcp-test");
        clientInfo.put("version", "0.0.1");
        params.set("clientInfo", clientInfo);

        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 1);
        envelope.put("method", "initialize");
        envelope.set("params", params);

        return test.send(HttpMethod.POST, "/mcp", null, MAPPER.writeValueAsString(envelope),
                "Content-Type", JSON,
                "Accept", ACCEPT_BOTH);
    }

    static void sendInitialized(ResourceBaseTest test, String sessionId) {
        String envelope = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        test.send(HttpMethod.POST, "/mcp", null, envelope,
                "Content-Type", JSON,
                "Accept", ACCEPT_BOTH,
                SESSION_HEADER, sessionId);
    }

    static JsonNode parseSseOrJson(String body) throws Exception {
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Empty response body");
        }
        String trimmed = body.trim();
        if (trimmed.startsWith("{")) {
            return MAPPER.readTree(trimmed);
        }
        for (String line : trimmed.split("\n")) {
            String l = line.trim();
            if (l.startsWith("data:")) {
                return MAPPER.readTree(l.substring(5).trim());
            }
        }
        throw new IllegalStateException("No JSON or SSE 'data:' line in body: " + body);
    }
}
