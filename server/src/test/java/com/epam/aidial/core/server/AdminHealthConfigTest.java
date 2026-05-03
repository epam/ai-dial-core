package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for the admin-only configuration health endpoint at
 * {@code GET /v1/admin/health/config}. Slice 1S.7 introduced the route; slice 2S.9
 * wired it to {@code MergedConfigStore.invalidEntities} and renamed the healthy
 * status from {@code "healthy"} to {@code "ok"} per design 02 §4.1.
 */
public class AdminHealthConfigTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminGetsHealthyEnvelope() {
        Response response = send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("ok", body.get("status").asText());
        JsonNode skipped = body.get("skipped");
        assertTrue(skipped.isArray() && skipped.isEmpty(),
                () -> "skipped array must be empty when no entities are invalid: " + response.body());
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                "authorization", "user"), 403);
    }
}
