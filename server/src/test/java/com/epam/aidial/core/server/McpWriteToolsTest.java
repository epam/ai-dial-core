package com.epam.aidial.core.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end MCP write-tools coverage (M.2.0). Exercises the full SDK roundtrip for the
 * three new write tools — including the per-controller routing split (POST for
 * ConfigResourceController types, PUT+If-None-Match/If-Match:* for ResourceController types)
 * and the request-side 412 disambiguation locked in the M.2.0 plan.
 */
class McpWriteToolsTest extends ResourceBaseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String MODEL_SPEC = """
            {"type": "chat",
             "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"}
            """;

    private static final String MODEL_SPEC_UPDATED = """
            {"type": "chat",
             "endpoint": "http://localhost:7001/openai/deployments/test-v2/chat/completions"}
            """;

    private static final String PROMPT_SPEC = """
            {"id":"prompt_id","name":"prompt","folderId":"folder","content":"hello"}
            """;

    @Test
    void toolsListExposesAllTools() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode tools = McpTestSupport.callMcp(this, sessionId, McpTestSupport.toolsListEnvelope(), null)
                .get("result").get("tools");
        assertEquals(8, tools.size());
        Set<String> names = new HashSet<>();
        for (JsonNode tool : tools) {
            names.add(tool.get("name").asText());
        }
        assertTrue(names.contains("dial_describe_schema"));
        assertTrue(names.contains("dial_list_resources"));
        assertTrue(names.contains("dial_get_resource"));
        assertTrue(names.contains("dial_create_resource"));
        assertTrue(names.contains("dial_update_resource"));
        assertTrue(names.contains("dial_delete_resource"));
    }

    @Test
    void createConfigResourceHappyPathReturns201WithEtag() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = createResource(sessionId, "models/public/mcp-create-1", MODEL_SPEC);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("created").asBoolean());
        assertEquals("models/public/mcp-create-1", body.get("id").asText());
        assertEquals("mcp-create-1", body.get("name").asText());
        assertNotNull(body.get("etag"));
        assertFalse(body.get("etag").isNull(), "Core returns ETag header for ConfigResourceController POST");
    }

    @Test
    void createConfigResourceExistingReturns409Error() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode first = createResource(sessionId, "models/public/mcp-create-conflict", MODEL_SPEC);
        assertFalse(first.get("isError").asBoolean());
        JsonNode second = createResource(sessionId, "models/public/mcp-create-conflict", MODEL_SPEC);
        assertTrue(second.get("isError").asBoolean());
        String text = second.get("content").get(0).get("text").asText();
        assertTrue(text.contains("HTTP 409"));
        assertTrue(text.contains("mcp-create-conflict"));
        assertTrue(text.contains("dial_update_resource"));
    }

    @Test
    void createResourceControllerTypeHappyPath() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = createResourceAsBucketOwner(sessionId, "prompts/" + bucket + "/mcp-prompt-1", PROMPT_SPEC);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("created").asBoolean());
        assertEquals("prompts/" + bucket + "/mcp-prompt-1", body.get("id").asText());
        Response get = send(HttpMethod.GET, "/v1/prompts/" + bucket + "/mcp-prompt-1", null, "");
        assertEquals(200, get.status());
    }

    @Test
    void createResourceControllerTypeExistingReturns409Error() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode first = createResourceAsBucketOwner(sessionId, "prompts/" + bucket + "/mcp-prompt-conflict", PROMPT_SPEC);
        assertFalse(first.get("isError").asBoolean(), () -> "Body: " + first.toString());
        JsonNode second = createResourceAsBucketOwner(sessionId, "prompts/" + bucket + "/mcp-prompt-conflict", PROMPT_SPEC);
        assertTrue(second.get("isError").asBoolean(),
                "duplicate create on ResourceController type: 412 from Core, MCP must remap to 409");
        String text = second.get("content").get(0).get("text").asText();
        assertTrue(text.contains("HTTP 409"), () -> "Expected HTTP 409 in: " + text);
    }

    @Test
    void updateConfigResourceHappyPathReturns200WithNewEtag() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode created = createResource(sessionId, "models/public/mcp-update-1", MODEL_SPEC);
        assertFalse(created.get("isError").asBoolean());
        JsonNode createdBody = MAPPER.readTree(created.get("content").get(0).get("text").asText());
        String createdEtag = createdBody.get("etag").asText();

        JsonNode updated = updateResource(sessionId, "models/public/mcp-update-1", MODEL_SPEC_UPDATED, null);
        assertFalse(updated.get("isError").asBoolean(), () -> "Body: " + updated.toString());
        JsonNode body = MAPPER.readTree(updated.get("content").get(0).get("text").asText());
        assertTrue(body.get("updated").asBoolean());
        assertNotNull(body.get("etag"));
        assertFalse(body.get("etag").isNull());
        assertFalse(createdEtag.equals(body.get("etag").asText()), "etag should change after update");
    }

    @Test
    void updateConfigResourceMissingReturns404Error() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = updateResource(sessionId, "models/public/mcp-update-missing", MODEL_SPEC, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("HTTP 404"));
        assertTrue(text.contains("mcp-update-missing"));
        assertTrue(text.contains("dial_create_resource"));
    }

    @Test
    void updateConfigResourceStaleIfMatchReturns412Error() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode created = createResource(sessionId, "models/public/mcp-update-stale", MODEL_SPEC);
        assertFalse(created.get("isError").asBoolean());

        JsonNode result = updateResource(sessionId, "models/public/mcp-update-stale", MODEL_SPEC_UPDATED, "stale-etag-value");
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("HTTP 412"), () -> "Expected HTTP 412 in: " + text);
        assertTrue(text.contains("stale-etag-value"));
    }

    @Test
    void updateResourceControllerTypeMissingReturns404Error() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "prompts/" + bucket + "/mcp-prompt-missing")
                .put("spec", PROMPT_SPEC);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_update_resource", args, null);
        assertTrue(result.get("isError").asBoolean(),
                "PUT+If-Match:* synthetic on missing prompts: Core returns 412, MCP remaps to 404");
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("HTTP 404"), () -> "Expected HTTP 404 in: " + text);
    }

    @Test
    void deleteResourceHappyPathResourceGone() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode created = createResource(sessionId, "models/public/mcp-delete-1", MODEL_SPEC);
        assertFalse(created.get("isError").asBoolean());

        JsonNode deleted = deleteResource(sessionId, "models/public/mcp-delete-1", true, null);
        assertFalse(deleted.get("isError").asBoolean(), () -> "Body: " + deleted.toString());
        JsonNode body = MAPPER.readTree(deleted.get("content").get(0).get("text").asText());
        assertTrue(body.get("deleted").asBoolean());
        assertEquals("models/public/mcp-delete-1", body.get("id").asText());

        Response get = send(HttpMethod.GET, "/v1/models/public/mcp-delete-1", null, "", "authorization", "admin");
        assertEquals(404, get.status());
    }

    @Test
    void deleteResourceWithoutConfirmIsRejectedMcpSide() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode created = createResource(sessionId, "models/public/mcp-delete-noconfirm", MODEL_SPEC);
        assertFalse(created.get("isError").asBoolean());

        JsonNode result = deleteResource(sessionId, "models/public/mcp-delete-noconfirm", false, null);
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("confirm must be true"));
        assertTrue(text.contains("mcp-delete-noconfirm"));

        Response get = send(HttpMethod.GET, "/v1/models/public/mcp-delete-noconfirm", null, "",
                "authorization", "admin");
        assertEquals(200, get.status(), "MCP must short-circuit before any Core hit when confirm is missing");
    }

    @Test
    void createResourceValidateOnlyDoesNotPersist() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "models/public/mcp-validate-only")
                .put("spec", MODEL_SPEC)
                .put("validate_only", true);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_create_resource", args, "admin");
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("validated").asBoolean());

        Response get = send(HttpMethod.GET, "/v1/models/public/mcp-validate-only", null, "",
                "authorization", "admin");
        assertEquals(404, get.status(), "validate_only must not persist");
    }

    @Test
    void createResourceValidateOnlyBadSpecReturns422() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "keys/platform/mcp-validate-bad-key")
                .put("spec", "{}")
                .put("validate_only", true);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_create_resource", args, "admin");
        assertTrue(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("422"), () -> "Expected 422 in: " + text);
    }

    @Test
    void createResourceValidateOnlyResolvesPrivateBucketInResponseId() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "applications/private/mcp-validate-private")
                .put("spec", "{\"endpoint\":\"http://x\",\"display_name\":\"vp\"}")
                .put("validate_only", true);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_create_resource", args, "admin");
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        String returnedId = body.get("id").asText();
        assertFalse(returnedId.contains("/private/"),
                () -> "validate_only response must echo resolved bucket, not the alias: " + returnedId);
        assertTrue(returnedId.startsWith("applications/") && returnedId.endsWith("/mcp-validate-private"),
                () -> "Unexpected canonical id: " + returnedId);
    }

    @Test
    void createResourceFilesIsRejectedWithRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = createResource(sessionId, "files/" + bucket + "/foo.txt", "{}");
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("files"));
        assertTrue(text.contains("dial_upload_file"));
    }

    @Test
    void createResourceSettingsIsRejectedWithRemediation() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = createResource(sessionId, "settings/platform/global", "{\"globalInterceptors\":[]}");
        assertTrue(result.get("isError").asBoolean());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("settings"));
        assertTrue(text.contains("singleton"));
        assertTrue(text.contains("dial_update_resource"),
                () -> "settings rejection must redirect to PUT-upsert path: " + text);
    }

    @Test
    void updateResourceSettingsUpsertsViaPut() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode result = updateResource(sessionId, "settings/platform/global",
                "{\"globalInterceptors\":[\"interceptor1\"]}", null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("updated").asBoolean());
        assertEquals("settings/platform/global", body.get("id").asText());

        Response get = send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        assertEquals(200, get.status());
        JsonNode getBody = MAPPER.readTree(get.body());
        assertEquals("api", getBody.get("source").asText(),
                "PUT through MCP must upsert the API blob; GET source flips from default to api");
        assertEquals(1, getBody.get("globalInterceptors").size());
    }

    @Test
    void deleteResourceSettingsClearsApiBlob() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        JsonNode put = updateResource(sessionId, "settings/platform/global",
                "{\"globalInterceptors\":[\"x\"]}", null);
        assertFalse(put.get("isError").asBoolean());

        JsonNode deleted = deleteResource(sessionId, "settings/platform/global", true, null);
        assertFalse(deleted.get("isError").asBoolean(), () -> "Body: " + deleted.toString());
        JsonNode body = MAPPER.readTree(deleted.get("content").get(0).get("text").asText());
        assertTrue(body.get("deleted").asBoolean());

        Response get = send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        JsonNode getBody = MAPPER.readTree(get.body());
        assertEquals("default", getBody.get("source").asText(),
                "DELETE through MCP must clear the API blob; source reverts to file/default");
    }

    @Test
    void deleteResourceFileFromUserBucket() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        Response uploaded = upload(HttpMethod.PUT, "/v1/files/" + bucket + "/mcp-delete-file.txt", null,
                "hello-mcp-delete");
        assertEquals(200, uploaded.status(), () -> "file upload failed: " + uploaded.body());

        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/mcp-delete-file.txt")
                .put("confirm", true);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_delete_resource", args, null);
        assertFalse(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        JsonNode body = MAPPER.readTree(result.get("content").get(0).get("text").asText());
        assertTrue(body.get("deleted").asBoolean());
        assertEquals("files/" + bucket + "/mcp-delete-file.txt", body.get("id").asText());

        Response get = send(HttpMethod.GET, "/v1/metadata/files/" + bucket + "/mcp-delete-file.txt", null, "");
        assertEquals(404, get.status(), "file must be gone after MCP delete");
    }

    @Test
    void deleteResourceFileMissingReturns404Error() throws Exception {
        String sessionId = McpTestSupport.handshake(this);
        ObjectNode args = MAPPER.createObjectNode()
                .put("id", "files/" + bucket + "/never-existed.txt")
                .put("confirm", true);
        JsonNode result = McpTestSupport.callTool(this, sessionId, "dial_delete_resource", args, null);
        assertTrue(result.get("isError").asBoolean(), () -> "Body: " + result.toString());
        String text = result.get("content").get(0).get("text").asText();
        assertTrue(text.contains("HTTP 404"), () -> "Expected HTTP 404 in: " + text);
        assertTrue(text.contains("never-existed.txt"));
    }

    private JsonNode createResource(String sessionId, String id, String spec) throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("id", id).put("spec", spec);
        return McpTestSupport.callTool(this, sessionId, "dial_create_resource", args, "admin");
    }

    private JsonNode createResourceAsBucketOwner(String sessionId, String id, String spec) throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("id", id).put("spec", spec);
        return McpTestSupport.callTool(this, sessionId, "dial_create_resource", args, null);
    }

    private JsonNode updateResource(String sessionId, String id, String spec, String ifMatch) throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("id", id).put("spec", spec);
        if (ifMatch != null) {
            args.put("if_match", ifMatch);
        }
        return McpTestSupport.callTool(this, sessionId, "dial_update_resource", args, "admin");
    }

    private JsonNode deleteResource(String sessionId, String id, boolean confirm, String ifMatch) throws Exception {
        ObjectNode args = MAPPER.createObjectNode().put("id", id).put("confirm", confirm);
        if (ifMatch != null) {
            args.put("if_match", ifMatch);
        }
        return McpTestSupport.callTool(this, sessionId, "dial_delete_resource", args, "admin");
    }
}
