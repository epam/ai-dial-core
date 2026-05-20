package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.3: GET reads on the {@code interceptors} platform-bucket type.
 * Platform-bucket reads are admin-only — non-admin callers receive 403 from
 * {@link com.epam.aidial.core.server.security.AdminRoleAuthorizationService}.
 */
public class ConfigInterceptorTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminReadsSingleInterceptor() {
        Response response = send(HttpMethod.GET, "/v1/interceptors/platform/interceptor1", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("interceptor1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("file", body.get("source").asText());
        assertTrue(body.has("endpoint"));
    }

    @Test
    @SneakyThrows
    void testAdminListsInterceptorsMetadata() {
        // U.0 (2026-05-20): per-bucket listings live on /v1/metadata/... and are blob-only.
        // File-sourced interceptors no longer surface here.
        Response response = send(HttpMethod.GET, "/v1/metadata/interceptors/platform/", null, "",
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
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/interceptor1", null, "",
                "authorization", "user"), 403);
        verify(send(HttpMethod.GET, "/v1/metadata/interceptors/platform/", null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testMissingInterceptorReturns404() {
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/no-such-interceptor", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testInvalidBucketHidesAs404() {
        // public/ is not bound for interceptors per EntityBucketBinding — must be 404, not 403/forbidden.
        verify(send(HttpMethod.GET, "/v1/interceptors/public/interceptor1", null, "",
                "authorization", "admin"), 404);
    }
}
