package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.7: admin-only configuration health endpoint at
 * {@code GET /v1/admin/health/config}. Phase 1 always reports healthy with an empty
 * {@code skipped} array — invalid-entity tracking ships in 2S.9.
 */
public class AdminHealthConfigTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminGetsHealthyEnvelope() {
        Response response = send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("healthy", body.get("status").asText());
        JsonNode skipped = body.get("skipped");
        assertTrue(skipped.isArray() && skipped.isEmpty(),
                () -> "Phase 1 must return empty skipped array: " + response.body());
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                "authorization", "user"), 403);
    }
}
