package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for GET reads on the {@code translators} platform-bucket type — mirrors
 * {@link ConfigInterceptorTest}. Platform-bucket reads are admin-only — non-admin callers receive
 * 403 from {@link com.epam.aidial.core.server.security.AdminRoleAuthorizationService}.
 */
public class ConfigTranslatorTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testFileTranslatorNotAddressableOnPerEntityGet() {
        // Per-entity GET is blob-only; the file-defined translator is not addressable.
        Response response = send(HttpMethod.GET, "/v1/translators/platform/translator1", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    @SneakyThrows
    void testFileTranslatorReadableViaFileConfigEndpoint() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/translators/translator1", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("translator1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertTrue(body.has("baseUrl"));
    }

    @Test
    @SneakyThrows
    void testAdminListsTranslatorsMetadata() {
        // Per-bucket listings live on /v1/metadata/... and are blob-only.
        // File-sourced translators do not surface here.
        Response response = send(HttpMethod.GET, "/v1/metadata/translators/platform/", null, "",
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
        // The platform/ admin gate fires BEFORE the entity lookup, so non-admin gets 403 even
        // though the file entry is no longer addressable on the per-entity surface.
        verify(send(HttpMethod.GET, "/v1/translators/platform/translator1", null, "",
                "authorization", "user"), 403);
        verify(send(HttpMethod.GET, "/v1/metadata/translators/platform/", null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testMissingTranslatorReturns404() {
        verify(send(HttpMethod.GET, "/v1/translators/platform/no-such-translator", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testInvalidBucketHidesAs404() {
        // public/ is not bound for translators per EntityBucketBinding — must be 404, not 403/forbidden.
        verify(send(HttpMethod.GET, "/v1/translators/public/translator1", null, "",
                "authorization", "admin"), 404);
    }
}
