package com.epam.aidial.core.mcp.tools;

import com.epam.aidial.core.mcp.client.DialResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.spec.McpSchema;
import io.vertx.core.MultiMap;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeleteResourceToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shape200ReturnsDeletedEnvelope() throws Exception {
        DialResponse resp = new DialResponse(200, "", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = DeleteResourceTool.shape(resp, id, id.bucket(), DeleteResourceTool.EtagIdiom.NONE, null);

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertTrue(body.get("deleted").asBoolean());
        assertEquals("models/public/m1", body.get("id").asText());
    }

    @Test
    void shape204ReturnsDeletedEnvelope() throws Exception {
        DialResponse resp = new DialResponse(204, "", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("interceptors/platform/i1");

        McpSchema.CallToolResult result = DeleteResourceTool.shape(resp, id, id.bucket(), DeleteResourceTool.EtagIdiom.NONE, null);

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertTrue(body.get("deleted").asBoolean());
        assertEquals("interceptors/platform/i1", body.get("id").asText());
    }

    @Test
    void shape404ReturnsNotFoundError() {
        DialResponse resp = new DialResponse(404, "missing", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = DeleteResourceTool.shape(resp, id, id.bucket(), DeleteResourceTool.EtagIdiom.NONE, null);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 404"));
    }

    @Test
    void shape412WithUserEtagReturnsPreconditionFailedError() {
        DialResponse resp = new DialResponse(412, "stale", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("prompts/bucket123/p1");

        McpSchema.CallToolResult result = DeleteResourceTool.shape(
                resp, id, id.bucket(), DeleteResourceTool.EtagIdiom.IF_MATCH_USER, "old-etag");

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 412"));
        assertTrue(text.contains("old-etag"));
    }

    @Test
    void specSchemaRequiresIdAndConfirm() {
        var spec = new DeleteResourceTool(null, null).spec();
        var inputSchema = spec.tool().inputSchema();
        assertTrue(inputSchema.required().contains("id"));
        assertTrue(inputSchema.required().contains("confirm"));
        assertFalse(inputSchema.required().contains("if_match"));
        assertEquals("dial_delete_resource", spec.tool().name());
    }
}
