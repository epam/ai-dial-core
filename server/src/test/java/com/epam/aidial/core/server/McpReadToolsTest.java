package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end MCP read-tools coverage (M.1.0). Exercises the SDK handshake, the three tool
 * registrations, and the transport-context auth-forwarding path against live Core.
 */
class McpReadToolsTest extends ResourceBaseTest {

    private static final String JSON = "application/json";
    private static final String SSE = "text/event-stream";
    private static final String ACCEPT_BOTH = JSON + ", " + SSE;
    private static final String SESSION_HEADER = "Mcp-Session-Id";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolsListExposesThreeReadTools() throws Exception {
        String sessionId = handshake();
        JsonNode tools = callMcp(sessionId, toolsListEnvelope(), null).get("result").get("tools");
        assertEquals(3, tools.size());
        java.util.Set<String> names = new java.util.HashSet<>();
        for (JsonNode tool : tools) {
            names.add(tool.get("name").asText());
        }
        assertTrue(names.contains("dial_describe_schema"));
        assertTrue(names.contains("dial_list_resources"));
        assertTrue(names.contains("dial_get_resource"));
    }

    @Test
    void describeSchemaForModelsReturnsParseableJsonSchema() throws Exception {
        String sessionId = handshake();
        JsonNode result = callTool(sessionId, "dial_describe_schema",
                MAPPER.createObjectNode().put("type", "models"), null);
        assertFalse(result.get("isError").asBoolean(), "describe_schema must succeed for pilot type");
        String schema = result.get("content").get(0).get("text").asText();
        JsonNode parsed = MAPPER.readTree(schema);
        assertNotNull(parsed.get("properties"));
    }

    @Test
    void describeSchemaForFilesReturnsNotYetImplementedEnvelope() throws Exception {
        String sessionId = handshake();
        JsonNode result = callTool(sessionId, "dial_describe_schema",
                MAPPER.createObjectNode().put("type", "files"), null);
        assertFalse(result.get("isError").asBoolean(),
                "files schema is a successful tool call returning a not-yet-implemented envelope");
        JsonNode envelope = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertEquals("files", envelope.get("type").asText());
        assertNotNull(envelope.get("error"));
        assertNotNull(envelope.get("hint"));
    }

    @Test
    void getResourceFetchesSeededModel() throws Exception {
        String sessionId = handshake();
        JsonNode result = callTool(sessionId, "dial_get_resource",
                MAPPER.createObjectNode().put("id", "models/public/test-model-v1"), null);
        assertFalse(result.get("isError").asBoolean(), "model GET must succeed for an authenticated caller");
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertEquals("test-model-v1", body.get("name").asText());
        assertTrue(body.get("etag").isNull(), "config-type GET ETag is null in Phase 1");
    }

    @Test
    void listRolesPlatformRequiresAdminAndReturnsTwoArrayEnvelope() throws Exception {
        String sessionId = handshake();
        JsonNode result = callTool(sessionId, "dial_list_resources",
                MAPPER.createObjectNode().put("path", "roles/platform/"), "admin");
        assertFalse(result.get("isError").asBoolean(), "admin caller must list roles successfully");
        JsonNode envelope = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertEquals("roles/platform/", envelope.get("path").asText());
        assertTrue(envelope.get("folders").isArray());
        assertEquals(0, envelope.get("folders").size());
    }

    @Test
    void listSettingsShortCircuitsWith405Envelope() throws Exception {
        String sessionId = handshake();
        JsonNode result = callTool(sessionId, "dial_list_resources",
                MAPPER.createObjectNode().put("path", "settings/platform/"), "admin");
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("settings/platform/global"));
    }

    @Test
    void getSettingsSingletonReturnsGlobalInterceptors() throws Exception {
        String sessionId = handshake();
        JsonNode result = callTool(sessionId, "dial_get_resource",
                MAPPER.createObjectNode().put("id", "settings/platform/global"), "admin");
        assertFalse(result.get("isError").asBoolean());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertNotNull(body.get("globalInterceptors"));
    }

    private String handshake() throws Exception {
        Response init = sendInitialize();
        assertEquals(200, init.status());
        String sessionId = init.headers().get(SESSION_HEADER);
        assertNotNull(sessionId);
        sendInitialized(sessionId);
        return sessionId;
    }

    private JsonNode callTool(String sessionId, String name, ObjectNode arguments, String authorization) throws Exception {
        ObjectNode params = MAPPER.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments);
        ObjectNode envelope = MAPPER.createObjectNode();
        envelope.put("jsonrpc", "2.0");
        envelope.put("id", 99);
        envelope.put("method", "tools/call");
        envelope.set("params", params);
        return callMcp(sessionId, MAPPER.writeValueAsString(envelope), authorization).get("result");
    }

    private JsonNode callMcp(String sessionId, String envelope, String authorization) throws Exception {
        Response response;
        if (authorization == null) {
            response = send(HttpMethod.POST, "/mcp", null, envelope,
                    "Content-Type", JSON,
                    "Accept", ACCEPT_BOTH,
                    SESSION_HEADER, sessionId);
        } else {
            response = send(HttpMethod.POST, "/mcp", null, envelope,
                    "Content-Type", JSON,
                    "Accept", ACCEPT_BOTH,
                    "Authorization", authorization,
                    SESSION_HEADER, sessionId);
        }
        assertEquals(200, response.status());
        return parseSseOrJson(response.body());
    }

    private String toolsListEnvelope() {
        return "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}";
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
