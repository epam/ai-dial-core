package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP integration tests for slice 1S.3: reads on the {@code schemas} public-bucket type.
 *
 * <p>U.0 (2026-05-20): per-bucket listings live on the sibling {@code /v1/metadata/...} route and
 * are blob-only — file-sourced schemas no longer surface here. The legacy single-entity surface
 * with an empty path now returns 404.
 */
public class ConfigSchemaTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminListsSchemasMetadata() {
        Response response = send(HttpMethod.GET, "/v1/metadata/schemas/platform/", null, "",
                "authorization", "admin");
        if (response.status() == 200) {
            JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals("FOLDER", body.get("nodeType").asText());
        } else {
            verify(response, 404);
        }
    }

    @Test
    void testEmptyPathSingleEntityUrlReturns404() {
        // U.0: the per-entity URL with empty name is no longer a listing surface.
        verify(send(HttpMethod.GET, "/v1/schemas/platform/", null, "", "authorization", "admin"), 404);
    }
}
