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

class UpdateResourceToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shape200ReturnsUpdatedEnvelopeWithEtag() throws Exception {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("ETag", "v2");
        DialResponse resp = new DialResponse(200, "{\"name\":\"m1\"}", headers);
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = UpdateResourceTool.shape(resp, id, id.bucket(), UpdateResourceTool.EtagIdiom.NONE, null);

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertTrue(body.get("updated").asBoolean());
        assertEquals("models/public/m1", body.get("id").asText());
        assertEquals("v2", body.get("etag").asText());
    }

    @Test
    void shape404ReturnsNotFoundError() {
        DialResponse resp = new DialResponse(404, "missing", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = UpdateResourceTool.shape(resp, id, id.bucket(), UpdateResourceTool.EtagIdiom.NONE, null);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 404"));
        assertTrue(text.contains("dial_create_resource"));
    }

    @Test
    void shape412FromSyntheticIfMatchStarTranslatesToNotFound() {
        DialResponse resp = new DialResponse(412, "Resource must exist", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("prompts/bucket123/p1");

        McpSchema.CallToolResult result = UpdateResourceTool.shape(
                resp, id, id.bucket(), UpdateResourceTool.EtagIdiom.IF_MATCH_STAR_SYNTHETIC, null);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 404"), "synthetic If-Match: * 412 must remap to 404");
        assertTrue(text.contains("prompts/bucket123/p1"));
    }

    @Test
    void shape412WithUserEtagReturnsPreconditionFailedError() {
        DialResponse resp = new DialResponse(412, "stale", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = UpdateResourceTool.shape(
                resp, id, id.bucket(), UpdateResourceTool.EtagIdiom.IF_MATCH_USER, "old-etag");

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 412"), "user-supplied If-Match keeps the 412 error");
        assertTrue(text.contains("old-etag"));
    }

    @Test
    void shapeOtherStatusReturnsHttpError() {
        DialResponse resp = new DialResponse(400, "bad spec", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = UpdateResourceTool.shape(resp, id, id.bucket(), UpdateResourceTool.EtagIdiom.NONE, null);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 400"));
    }

    @Test
    void specSchemaRequiresIdAndSpecAndAllowsIfMatch() {
        var spec = new UpdateResourceTool(null, null).spec();
        var inputSchema = spec.tool().inputSchema();
        assertTrue(inputSchema.required().contains("id"));
        assertTrue(inputSchema.required().contains("spec"));
        assertFalse(inputSchema.required().contains("if_match"));
        assertFalse(inputSchema.required().contains("validate_only"));
        assertEquals("dial_update_resource", spec.tool().name());
    }
}
