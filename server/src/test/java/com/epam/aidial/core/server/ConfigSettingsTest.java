package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.3: singleton {@code settings} surface at
 * {@code /v1/settings/platform/global}.
 *
 * <p>Phase 1 ships GET only — the singleton has no listing surface, no create surface, and PUT/DELETE
 * are deferred to Phase 2. {@code Allow: GET, PUT, DELETE} is advertised on every 405 response per
 * the slice register row's locked contract.
 */
public class ConfigSettingsTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testGetSingletonReturnsDefaultSource() {
        // The default test config does not populate globalInterceptors — projection must report "default".
        Response response = send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("global", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("default", body.get("source").asText());
        assertTrue(body.has("globalInterceptors"));
        assertTrue(body.get("globalInterceptors").isArray());
        assertEquals(0, body.get("globalInterceptors").size());
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testUnknownSingletonNameReturns404() {
        verify(send(HttpMethod.GET, "/v1/settings/platform/something-else", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testListingPathReturns405WithAllow() {
        Response response = send(HttpMethod.GET, "/v1/settings/platform/", null, "",
                "authorization", "admin");
        verify(response, 405);
        assertEquals("GET, PUT, DELETE", response.headers().get("Allow"));
    }

    @Test
    void testPostSingletonReturns405WithAllow() {
        Response response = send(HttpMethod.POST, "/v1/settings/platform/global", null, "{}",
                "authorization", "admin");
        verify(response, 405);
        assertEquals("GET, PUT, DELETE", response.headers().get("Allow"));
    }

    @Test
    void testPutSingletonReturns405WithAllow() {
        Response response = send(HttpMethod.PUT, "/v1/settings/platform/global", null, "{}",
                "authorization", "admin");
        verify(response, 405);
        assertEquals("GET, PUT, DELETE", response.headers().get("Allow"));
    }

    @Test
    void testDeleteSingletonReturns405WithAllow() {
        Response response = send(HttpMethod.DELETE, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        verify(response, 405);
        assertEquals("GET, PUT, DELETE", response.headers().get("Allow"));
    }
}
