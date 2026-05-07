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

class GetResourceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void detailedReturnsBodyAugmentedWithNullEtagForConfigType() throws Exception {
        DialResponse resp = new DialResponse(200,
                "{\"name\":\"gpt-4\",\"displayName\":\"GPT-4\",\"endpoint\":\"http://x\"}",
                MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/gpt-4");

        McpSchema.CallToolResult result = GetResourceTool.shape(resp, id);

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertEquals("gpt-4", body.get("name").asText());
        assertTrue(body.get("etag").isNull());
    }

    @Test
    void settingsSingletonIsRetrievedAndAugmentedWithEtag() throws Exception {
        DialResponse resp = new DialResponse(200,
                "{\"name\":\"global\",\"globalInterceptors\":[],\"retriableErrorCodes\":[]}",
                MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("settings/platform/global");

        McpSchema.CallToolResult result = GetResourceTool.shape(resp, id);

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode body = MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
        assertTrue(body.has("globalInterceptors"));
        assertTrue(body.get("etag").isNull());
    }

    @Test
    void coreErrorIsSurfacedAsStructuredHttpError() {
        DialResponse resp = new DialResponse(404, "not found", MultiMap.caseInsensitiveMultiMap());
        ResourceId id = ResourceId.parse("models/public/missing");

        McpSchema.CallToolResult result = GetResourceTool.shape(resp, id);

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 404"));
    }
}
