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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void toolsListExposesAllSixTools() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode tools = McpTestSupport.callMcp(this, sessionId, McpTestSupport.toolsListEnvelope(), null)
                .get("result").get("tools");
        assertEquals(6, tools.size());
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
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_describe_schema",
                MAPPER.createObjectNode().put("type", "models"), null);
        assertFalse(result.get("isError").asBoolean(), "describe_schema must succeed for pilot type");
        String schema = result.get("content").get(0).get("text").asText();
        JsonNode parsed = MAPPER.readTree(schema);
        assertNotNull(parsed.get("properties"));
    }

    @Test
    void describeSchemaForFilesReturnsNotYetImplementedEnvelope() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_describe_schema",
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
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_get_resource",
                MAPPER.createObjectNode().put("id", "models/public/test-model-v1"), null);
        assertFalse(result.get("isError").asBoolean(), "model GET must succeed for an authenticated caller");
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertEquals("test-model-v1", body.get("name").asText());
        assertTrue(body.get("etag").isNull(), "config-type GET ETag is null in Phase 1");
    }

    @Test
    void listRolesPlatformRequiresAdminAndReturnsTwoArrayEnvelope() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_list_resources",
                MAPPER.createObjectNode().put("path", "roles/platform/"), "admin");
        assertFalse(result.get("isError").asBoolean(), "admin caller must list roles successfully");
        JsonNode envelope = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertEquals("roles/platform/", envelope.get("path").asText());
        assertTrue(envelope.get("folders").isArray());
        assertEquals(0, envelope.get("folders").size());
    }

    @Test
    void listSettingsShortCircuitsWith405Envelope() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_list_resources",
                MAPPER.createObjectNode().put("path", "settings/platform/"), "admin");
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("settings/platform/global"));
    }

    @Test
    void listConversationsSplitsFoldersAndItems() throws Exception {
        Response put = send(HttpMethod.PUT, "/v1/conversations/" + bucket + "/folder/c1", null,
                CONVERSATION_BODY_1, "Content-Type", "application/json");
        assertEquals(200, put.status(), put.body());

        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_list_resources",
                MAPPER.createObjectNode().put("path", "conversations/" + bucket + "/"), null);
        assertFalse(result.get("isError").asBoolean());
        JsonNode envelope = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertEquals("conversations/" + bucket + "/", envelope.get("path").asText());
        assertEquals(1, envelope.get("folders").size(),
                "PUT /folder/c1 surfaces 'folder' as a sub-prefix in folders[]");
        assertEquals("folder", envelope.get("folders").get(0).get("name").asText());
        assertEquals(0, envelope.get("items").size(),
                "non-recursive list shows only direct children — leaf c1 sits one level deeper");
    }

    @Test
    void listConversationsRecursiveFlattensTreeIntoItems() throws Exception {
        Response put = send(HttpMethod.PUT, "/v1/conversations/" + bucket + "/folder/c2", null,
                CONVERSATION_BODY_1, "Content-Type", "application/json");
        assertEquals(200, put.status(), put.body());

        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode().put("path", "conversations/" + bucket + "/");
        args.put("recursive", true);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_list_resources", args, null);
        assertFalse(result.get("isError").asBoolean());
        JsonNode envelope = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        boolean foundLeaf = false;
        for (JsonNode item : envelope.get("items")) {
            if (item.get("id").asText().endsWith("/folder/c2")) {
                foundLeaf = true;
                break;
            }
        }
        assertTrue(foundLeaf, "recursive=true must surface the deep leaf in items[]");
    }

    @Test
    void recursiveOnFlatTypeIsRejected() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode().put("path", "models/public/");
        args.put("recursive", true);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_list_resources", args, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("recursive"));
        assertTrue(text.contains("models"));
    }

    @Test
    void cursorOnFlatTypeIsRejected() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("path", "models/public/")
                .put("cursor", "anything");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_list_resources", args, null);
        assertTrue(result.get("isError").asBoolean(),
                "flat types are single-page; passing cursor must surface a remediation hint");
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("cursor"));
    }

    @Test
    void getSettingsSingletonReturnsGlobalInterceptors() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_get_resource",
                MAPPER.createObjectNode().put("id", "settings/platform/global"), "admin");
        assertFalse(result.get("isError").asBoolean());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertNotNull(body.get("globalInterceptors"));
    }
}
