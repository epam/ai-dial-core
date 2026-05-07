package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end MCP {@code dial_publish_resource} coverage (M.4.0). Exercises the publication-create
 * lifecycle (PENDING state, source/target URL composition from {@code id} + {@code target}) and
 * preflight validation against the real Core publication controller.
 */
class McpPublishToolTest extends ResourceBaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void publishPrivateConversationCreatesPendingPublication() throws Exception {
        Response put = resourceRequest(HttpMethod.PUT, "/folder/c1", CONVERSATION_BODY_1);
        assertEquals(200, put.status(), () -> "fixture PUT failed: " + put.body());

        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "conversations/private/folder/c1")
                .put("target", "public/conversations/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("published").asBoolean());
        assertEquals("conversations/" + bucket + "/folder/c1", body.get("id").asText());

        JsonNode publication = body.get("publication");
        assertEquals("PENDING", publication.get("status").asText());
        assertEquals("public/conversations/", publication.get("targetFolder").asText());
        assertTrue(publication.get("url").asText().startsWith("publications/" + bucket + "/"));
        JsonNode resource = publication.get("resources").get(0);
        assertEquals("ADD", resource.get("action").asText());
        assertEquals("conversations/" + bucket + "/folder/c1", resource.get("sourceUrl").asText());
        assertEquals("conversations/public/conversations/c1", resource.get("targetUrl").asText());

        Response list = send(HttpMethod.POST, "/v1/ops/publication/list", null,
                "{\"url\":\"publications/" + bucket + "/\"}");
        assertEquals(200, list.status());
        JsonNode listBody = MAPPER.readTree(list.body());
        assertEquals(1, listBody.get("publications").size(),
                "publication-list must surface the PENDING entry created by the MCP tool");
    }

    @Test
    void publishMultiLevelNamePlacesLeafAtTargetRoot() throws Exception {
        Response put = resourceRequest(HttpMethod.PUT, "/a/b/c1", CONVERSATION_BODY_1);
        assertEquals(200, put.status(), () -> "fixture PUT failed: " + put.body());

        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "conversations/private/a/b/c1")
                .put("target", "public/conversations/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode resource = MAPPER.readTree(result.get("content").get(0).get("text").asText())
                .get("publication").get("resources").get(0);
        assertEquals("conversations/" + bucket + "/a/b/c1", resource.get("sourceUrl").asText());
        assertEquals("conversations/public/conversations/c1", resource.get("targetUrl").asText(),
                "Intermediate folders strip from targetUrl — leaf is placed at the target root.");
    }

    @Test
    void publishWithExplicitBucketSkipsPrivateAlias() throws Exception {
        Response put = resourceRequest(HttpMethod.PUT, "/folder/c2", CONVERSATION_BODY_1);
        assertEquals(200, put.status(), () -> "fixture PUT failed: " + put.body());

        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "conversations/" + bucket + "/folder/c2")
                .put("target", "public/conversations/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode resource = MAPPER.readTree(result.get("content").get(0).get("text").asText())
                .get("publication").get("resources").get(0);
        assertEquals("conversations/" + bucket + "/folder/c2", resource.get("sourceUrl").asText(),
                "Explicit bucket short-circuits the alias-resolution branch.");
    }

    @Test
    void publishMissingIdReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode().put("target", "public/conversations/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("'id' argument is required"));
    }

    @Test
    void publishMissingTargetReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode().put("id", "conversations/private/c1");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("'target' argument is required"));
    }

    @Test
    void publishTargetWithoutPublicPrefixReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "conversations/private/c1")
                .put("target", "random/folder/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("must start with 'public/'"));
    }

    @Test
    void publishTargetWithoutTrailingSlashReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "conversations/private/c1")
                .put("target", "public/conversations");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("must end with '/'"));
    }

    @Test
    void publishMalformedIdReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "conversations/private/")
                .put("target", "public/conversations/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("Malformed id"));
    }

    @Test
    void publishUnknownTypeReturnsRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "bogus/private/c1")
                .put("target", "public/conversations/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertTrue(result.get("isError").asBoolean());
        assertTrue(result.get("content").get(0).get("text").asText().contains("Unknown type 'bogus'"));
    }

    @Test
    void publishMissingSourceReturnsUpstreamError() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "conversations/private/never-existed")
                .put("target", "public/conversations/");
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_publish_resource", args, null);
        assertTrue(result.get("isError").asBoolean(),
                "Core rejects publication of non-existent source — MCP must surface the error.");
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.startsWith("HTTP "),
                () -> "expected HTTP-shaped upstream error envelope, got: " + text);
    }
}
