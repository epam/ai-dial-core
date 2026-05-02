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
 * HTTP integration tests for slice 1S.3: GET reads on the {@code roles} platform-bucket type.
 */
public class ConfigRoleTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminReadsSingleRole() {
        Response response = send(HttpMethod.GET, "/v1/roles/platform/default", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("default", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("file", body.get("source").asText());
        assertTrue(body.has("limits"));
    }

    @Test
    @SneakyThrows
    void testAdminListsRoles() {
        Response response = send(HttpMethod.GET, "/v1/roles/platform/", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("roles", body.get("entityType").asText());
        assertEquals("platform", body.get("bucket").asText());
        assertFalse(body.has("nextCursor"));
        JsonNode items = body.get("items");
        assertTrue(items.isArray() && items.size() >= 3);
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/roles/platform/default", null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testMissingRoleReturns404() {
        verify(send(HttpMethod.GET, "/v1/roles/platform/no-such-role", null, "",
                "authorization", "admin"), 404);
    }
}
