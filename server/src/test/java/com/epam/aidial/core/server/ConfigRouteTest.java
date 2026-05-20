package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP integration tests for slice 1S.3: GET reads on the {@code routes} platform-bucket type.
 * U.0 (2026-05-20): per-bucket listings live on /v1/metadata/... and are blob-only.
 */
public class ConfigRouteTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminReadsSingleRoute() {
        Response response = send(HttpMethod.GET, "/v1/routes/platform/plain", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("plain", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("file", body.get("source").asText());
    }

    @Test
    @SneakyThrows
    void testAdminListsRoutesMetadata() {
        Response response = send(HttpMethod.GET, "/v1/metadata/routes/platform/", null, "",
                "authorization", "admin");
        if (response.status() == 200) {
            JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals("FOLDER", body.get("nodeType").asText());
        } else {
            verify(response, 404);
        }
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/routes/platform/plain", null, "",
                "authorization", "user"), 403);
    }
}
