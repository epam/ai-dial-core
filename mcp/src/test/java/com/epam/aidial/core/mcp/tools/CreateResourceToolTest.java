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

class CreateResourceToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shape201ReturnsCreatedEnvelopeWithEtag() throws Exception {
        MultiMap headers = MultiMap.caseInsensitiveMultiMap().add("ETag", "abc123");
        DialResponse resp = new DialResponse(201, "{\"name\":\"m1\"}", headers);
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = CreateResourceTool.shape(resp, id, id.bucket(), CreateResourceTool.EtagIdiom.NONE);

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertTrue(body.get("created").asBoolean());
        assertEquals("models/public/m1", body.get("id").asText());
        assertEquals("m1", body.get("name").asText());
        assertEquals("abc123", body.get("etag").asText());
    }

    @Test
    void shape201WithoutEtagSetsEtagToNull() throws Exception {
        DialResponse resp = new DialResponse(201, "{}", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = CreateResourceTool.shape(resp, id, id.bucket(), CreateResourceTool.EtagIdiom.NONE);

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertTrue(body.get("etag").isNull());
    }

    @Test
    void shape409ReturnsConflictError() {
        DialResponse resp = new DialResponse(409, "exists", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = CreateResourceTool.shape(resp, id, id.bucket(), CreateResourceTool.EtagIdiom.NONE);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 409"));
        assertTrue(text.contains("models/public/m1"));
        assertTrue(text.contains("dial_update_resource"));
    }

    @Test
    void shape412FromIfNoneMatchTranslatesToConflictError() {
        DialResponse resp = new DialResponse(412, "Resource already exists", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("prompts/bucket123/p1");

        McpSchema.CallToolResult result = CreateResourceTool.shape(
                resp, id, id.bucket(), CreateResourceTool.EtagIdiom.IF_NONE_MATCH_STAR);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 409"), "412 with If-None-Match: * idiom should remap to 409");
        assertTrue(text.contains("prompts/bucket123/p1"));
    }

    @Test
    void shapeOtherStatusReturnsHttpError() {
        DialResponse resp = new DialResponse(403, "forbidden", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = CreateResourceTool.shape(resp, id, id.bucket(), CreateResourceTool.EtagIdiom.NONE);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 403"));
        assertTrue(text.contains("dial_describe_schema"));
    }

    @Test
    void shapeValidate200ReturnsValidatedEnvelope() throws Exception {
        String coreBody = "{\"valid\":1,\"failed\":0,\"results\":[{\"entityId\":\"Model:m1\",\"status\":\"valid\"}]}";
        DialResponse resp = new DialResponse(200, coreBody, MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = CreateResourceTool.shapeValidate(resp, id, id.bucket());

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertTrue(body.get("validated").asBoolean());
        assertEquals("models/public/m1", body.get("id").asText());
        assertEquals(1, body.get("results").size());
        assertEquals("valid", body.get("results").get(0).get("status").asText());
    }

    @Test
    void shapeValidate422ReturnsValidationError() {
        String coreBody = "{\"valid\":0,\"failed\":1,\"results\":[{\"entityId\":\"Model:m1\",\"status\":\"FAILED\",\"error\":\"endpoint required\"}]}";
        DialResponse resp = new DialResponse(422, coreBody, MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/m1");

        McpSchema.CallToolResult result = CreateResourceTool.shapeValidate(resp, id, id.bucket());

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("422"));
        assertTrue(text.contains("endpoint required"));
    }

    @Test
    void specSchemaRequiresIdAndSpec() {
        var spec = new CreateResourceTool(null, null).spec();
        var inputSchema = spec.tool().inputSchema();
        assertTrue(inputSchema.required().contains("id"));
        assertTrue(inputSchema.required().contains("spec"));
        assertFalse(inputSchema.required().contains("validate_only"));
        assertEquals("dial_create_resource", spec.tool().name());
    }

    @Test
    void buildValidateEnvelopeUsesKindMappingAndPrecheck() throws Exception {
        JsonNode spec = MAPPER.readTree("{\"endpoint\":\"http://x\"}");
        String envelope = CreateResourceTool.buildValidateEnvelope("models", "m1", spec);
        JsonNode parsed = MAPPER.readTree(envelope);
        assertEquals(true, parsed.get("precheck").asBoolean());
        assertEquals("Model", parsed.get("manifests").get(0).get("kind").asText());
        assertEquals("m1", parsed.get("manifests").get(0).get("name").asText());
        assertEquals("http://x", parsed.get("manifests").get(0).get("spec").get("endpoint").asText());
    }

    @Test
    void isResourceControllerTypeExcludesFiles() {
        assertTrue(ResourceId.parse("prompts/b/p1").isResourceControllerType());
        assertTrue(ResourceId.parse("conversations/b/c1").isResourceControllerType());
        assertTrue(ResourceId.parse("applications/b/a1").isResourceControllerType());
        assertTrue(ResourceId.parse("toolsets/b/t1").isResourceControllerType());
        assertFalse(ResourceId.parse("files/b/f.txt").isResourceControllerType());
        assertFalse(ResourceId.parse("models/public/m1").isResourceControllerType());
        assertFalse(ResourceId.parse("roles/platform/r1").isResourceControllerType());
    }
}
