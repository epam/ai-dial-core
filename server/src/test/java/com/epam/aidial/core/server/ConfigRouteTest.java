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
 * HTTP integration tests for slice 1S.3: GET reads on the {@code routes} platform-bucket type.
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
    void testAdminListsRoutes() {
        Response response = send(HttpMethod.GET, "/v1/routes/platform/", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("routes", body.get("entityType").asText());
        assertEquals("platform", body.get("bucket").asText());
        assertFalse(body.get("hasMore").asBoolean());
        assertTrue(body.get("items").isArray());
        assertTrue(body.get("items").size() >= 5);
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/routes/platform/plain", null, "",
                "authorization", "user"), 403);
    }
}
