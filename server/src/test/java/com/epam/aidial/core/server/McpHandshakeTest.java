package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end MCP handshake against the real {@link com.epam.aidial.core.mcp.transport.VertxMcpTransportProvider}
 * wired in {@code AiDial.start()} when {@code mcp.enabled = true}.
 */
class McpHandshakeTest extends ResourceBaseTest {

    private static final String JSON = "application/json";
    private static final String SSE = "text/event-stream";
    private static final String ACCEPT_BOTH = JSON + ", " + SSE;
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void initializeReturns200WithSessionIdAndServerInfo() throws Exception {
        Response response = sendInitialize();
        assertEquals(200, response.status());
        String sessionId = response.headers().get(SESSION_HEADER);
        assertNotNull(sessionId);
        assertTrue(!sessionId.isBlank(), "Mcp-Session-Id must not be blank");

        JsonNode body = MAPPER.readTree(response.body());
        assertNotNull(body.get("result"), "result must be present");
        assertNotNull(body.get("result").get("serverInfo"), "result.serverInfo must be present");
    }

    @Test
    void toolsListReturnsEmptyArray() throws Exception {
        String sessionId = sendInitialize().headers().get(SESSION_HEADER);
        sendInitialized(sessionId);

        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        Response response = send(HttpMethod.POST, "/mcp", null, envelope,
                "Content-Type", JSON,
                "Accept", ACCEPT_BOTH,
                SESSION_HEADER, sessionId);

        assertEquals(200, response.status());
        JsonNode body = parseSseOrJson(response.body());
        assertNotNull(body.get("result"));
        JsonNode tools = body.get("result").get("tools");
        assertNotNull(tools);
        assertTrue(tools.isArray());
        assertEquals(0, tools.size());
    }

    @Test
    void postWithUnknownSessionReturns404() {
        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}";
        Response response = send(HttpMethod.POST, "/mcp", null, envelope,
                "Content-Type", JSON,
                "Accept", ACCEPT_BOTH,
                SESSION_HEADER, "00000000-deadbeef");
        assertEquals(404, response.status());
    }

    @Test
    void getWithoutSessionIdReturns400() {
        Response response = send(HttpMethod.GET, "/mcp", null, null,
                "Accept", SSE);
        assertEquals(400, response.status());
    }

    @Test
    void deleteSessionThenPostReturns404() throws Exception {
        String sessionId = sendInitialize().headers().get(SESSION_HEADER);

        Response delete = send(HttpMethod.DELETE, "/mcp", null, null, SESSION_HEADER, sessionId);
        assertEquals(200, delete.status());

        String envelope = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
        Response after = send(HttpMethod.POST, "/mcp", null, envelope,
                "Content-Type", JSON,
                "Accept", ACCEPT_BOTH,
                SESSION_HEADER, sessionId);
        assertEquals(404, after.status());
    }

    private Response sendInitialize() throws Exception {
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

        return send(HttpMethod.POST, "/mcp", null, MAPPER.writeValueAsString(envelope),
                "Content-Type", JSON,
                "Accept", ACCEPT_BOTH);
    }

    private void sendInitialized(String sessionId) {
        String envelope = "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}";
        send(HttpMethod.POST, "/mcp", null, envelope,
                "Content-Type", JSON,
                "Accept", ACCEPT_BOTH,
                SESSION_HEADER, sessionId);
    }

    private static JsonNode parseSseOrJson(String body) throws Exception {
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
