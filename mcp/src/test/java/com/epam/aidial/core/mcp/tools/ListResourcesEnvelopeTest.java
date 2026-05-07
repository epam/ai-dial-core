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

class ListResourcesEnvelopeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void shapesTwoArrayEnvelopeForFlatType() throws Exception {
        String coreBody = "{\"entityType\":\"models\",\"bucket\":\"public\",\"items\":["
                + "{\"name\":\"m1\",\"displayName\":\"M1\",\"displayVersion\":\"1\",\"status\":\"valid\",\"description\":\"d\"}"
                + "],\"hasMore\":false}";
        DialResponse resp = new DialResponse(200, coreBody, MultiMap.caseInsensitiveMultiMap());

        McpSchema.CallToolResult result = ListResourcesTool.shape(resp, "models", "public", "public", "summary");

        assertFalse(Boolean.TRUE.equals(result.isError()));
        JsonNode envelope = parseFirstText(result);
        assertEquals("models/public/", envelope.get("path").asText());
        assertTrue(envelope.get("folders").isArray());
        assertEquals(0, envelope.get("folders").size());
        assertTrue(envelope.get("nextCursor").isNull());
        assertEquals(false, envelope.get("hasMore").asBoolean());
        assertEquals(false, envelope.get("truncated").asBoolean());
        JsonNode item = envelope.get("items").get(0);
        assertEquals("resource", item.get("kind").asText());
        assertEquals("models/public/m1", item.get("id").asText());
        assertTrue(item.get("etag").isNull());
    }

    @Test
    void summaryProjectionForModelsKeepsTableFieldsOnly() throws Exception {
        String coreBody = "{\"items\":["
                + "{\"name\":\"m1\",\"displayName\":\"M1\",\"endpoint\":\"http://x\",\"upstreams\":[]}],\"hasMore\":false}";
        DialResponse resp = new DialResponse(200, coreBody, MultiMap.caseInsensitiveMultiMap());

        JsonNode envelope = parseFirstText(ListResourcesTool.shape(resp, "models", "public", "public", "summary"));
        JsonNode item = envelope.get("items").get(0);

        assertTrue(item.has("displayName"));
        assertFalse(item.has("endpoint"), "summary projection drops endpoint for models");
        assertFalse(item.has("upstreams"), "summary projection drops upstreams for models");
    }

    @Test
    void coreErrorBecomesStructuredHttpError() throws Exception {
        DialResponse resp = new DialResponse(403, "denied", MultiMap.caseInsensitiveMultiMap());

        McpSchema.CallToolResult result = ListResourcesTool.shape(resp, "roles", "platform", "platform", "summary");

        assertTrue(Boolean.TRUE.equals(result.isError()));
        String text = ((McpSchema.TextContent) result.content().get(0)).text();
        assertTrue(text.contains("HTTP 403"));
    }

    @Test
    void detailedFormatPreservesAllItemFields() throws Exception {
        String coreBody = "{\"items\":[{\"name\":\"r1\",\"description\":\"d\",\"status\":\"valid\","
                + "\"limits\":{\"foo\":{}}}],\"hasMore\":false}";
        DialResponse resp = new DialResponse(200, coreBody, MultiMap.caseInsensitiveMultiMap());

        JsonNode envelope = parseFirstText(ListResourcesTool.shape(resp, "roles", "platform", "platform", "detailed"));
        JsonNode item = envelope.get("items").get(0);

        assertTrue(item.has("limits"), "detailed format preserves all original fields");
    }

    private static JsonNode parseFirstText(McpSchema.CallToolResult result) throws Exception {
        return MAPPER.readTree(((McpSchema.TextContent) result.content().get(0)).text());
    }
}
