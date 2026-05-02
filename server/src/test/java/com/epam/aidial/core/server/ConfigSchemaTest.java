package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.3: GET reads on the {@code schemas} public-bucket type.
 *
 * <p>Schemas live in {@code public/} (per EntityBucketBinding) so reads are open to authenticated
 * callers; only admin sees the {@code source} marker.
 */
public class ConfigSchemaTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminListsSchemas() {
        Response response = send(HttpMethod.GET, "/v1/schemas/public/", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("schemas", body.get("entityType").asText());
        assertEquals("public", body.get("bucket").asText());
        assertFalse(body.get("hasMore").asBoolean());
        assertFalse(body.has("nextCursor"));
        JsonNode items = body.get("items");
        assertTrue(items.isArray() && !items.isEmpty(),
                () -> "Expected schemas: " + response.body());
        for (JsonNode item : items) {
            assertEquals("valid", item.get("status").asText());
            assertEquals("file", item.get("source").asText());
            assertTrue(item.has("name"));
            // Schema body fields are flattened onto the item — $schema / $id from the JSON string.
            assertTrue(item.has("$schema"), () -> "Expected $schema field: " + item);
        }
    }

    @Test
    @SneakyThrows
    void testUserListsSchemasWithoutSource() {
        Response response = send(HttpMethod.GET, "/v1/schemas/public/", null, "",
                "authorization", "user");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        for (JsonNode item : body.get("items")) {
            assertFalse(item.has("source"),
                    () -> "Source must be Owner-only on public/ types: " + item);
            assertEquals("valid", item.get("status").asText());
        }
    }
}
