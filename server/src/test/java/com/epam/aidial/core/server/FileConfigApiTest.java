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
 * HTTP integration tests for slice U.1: the {@code /v1/admin/config/file/{type}[/{name}]}
 * read-only surface for file-sourced configuration entries.
 *
 * <p>Authorization: admin role for every supported type EXCEPT {@code keys}, which requires
 * the security-admin tier — file-sourced {@code Config.keys} keeps the legacy map-key-as-secret
 * format per OQ-12, so even leaking key names via URL/listing exposes secrets.
 */
public class FileConfigApiTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testListFileModels() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode items = body.get("items");
        assertTrue(items.isArray(), () -> "Expected items array: " + response.body());
        boolean foundFileModel = false;
        for (JsonNode item : items) {
            if ("test-model-v1".equals(item.get("name").asText())) {
                foundFileModel = true;
                break;
            }
        }
        assertTrue(foundFileModel,
                () -> "Expected file model 'test-model-v1' in listing: " + response.body());
    }

    @Test
    @SneakyThrows
    void testGetFileModelByName() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models/test-model-v1", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("test-model-v1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertTrue(body.has("endpoint"),
                () -> "Expected endpoint field on file model: " + response.body());
        assertFalse(body.has("source"),
                () -> "U.1: source field must not appear in any response: " + response.body());
    }

    @Test
    void testGetMissingFileModelReturns404() {
        verify(send(HttpMethod.GET, "/v1/admin/config/file/models/no-such-model", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/admin/config/file/models", null, "",
                "authorization", "user"), 403);
        verify(send(HttpMethod.GET, "/v1/admin/config/file/models/test-model-v1", null, "",
                "authorization", "user"), 403);
    }

    @Test
    @SneakyThrows
    void testListFileRoles() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/roles", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertTrue(body.get("items").isArray());
    }

    @Test
    @SneakyThrows
    void testGetFileInterceptorByName() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/interceptors/interceptor1", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("interceptor1", body.get("name").asText());
        assertTrue(body.has("endpoint"));
    }

    @Test
    void testKeysRequireSecurityAdmin() {
        // Plain admin is rejected — file keys map keys equal secrets per OQ-12.
        verify(send(HttpMethod.GET, "/v1/admin/config/file/keys", null, "",
                "authorization", "admin"), 403);
        verify(send(HttpMethod.GET, "/v1/admin/config/file/keys/proxyKey1", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    @SneakyThrows
    void testKeysListAllowedForSecurityAdmin() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/keys", null, "",
                "authorization", "security-admin");
        verify(response, 200);
        JsonNode items = ProxyUtil.MAPPER.readTree(response.body()).get("items");
        assertTrue(items.isArray() && !items.isEmpty(),
                () -> "Expected non-empty file keys listing: " + response.body());
        // Regression guard on the OQ-12 security property: file-sourced keys keep the legacy
        // map-key-as-secret format, so "name" in this listing IS the plaintext secret. The
        // security-admin gate above is what protects it. If the listing is ever changed to
        // sanitize the name (e.g., emit a digest or opaque id), this assertion must change in
        // lockstep — silent drift in either direction would be a security-relevant regression.
        boolean foundPlaintextKeyName = false;
        for (JsonNode item : items) {
            if ("proxyKey1".equals(item.get("name").asText())) {
                foundPlaintextKeyName = true;
                break;
            }
        }
        assertTrue(foundPlaintextKeyName,
                () -> "Expected file map key 'proxyKey1' (plaintext secret) in listing: " + response.body());
    }

    @Test
    @SneakyThrows
    void testKeysGetAllowedForSecurityAdminAndSecretMasked() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/keys/proxyKey1", null, "",
                "authorization", "security-admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("proxyKey1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("***", body.get("key").asText(),
                () -> "Secret must be masked without reveal_secrets: " + response.body());
    }

    @Test
    @SneakyThrows
    void testKeysGetRevealSecretsUnmasksForSecurityAdmin() {
        // ?reveal_secrets=true switches to BLOB_MAPPER, which surfaces the in-memory plaintext value.
        // For file-sourced keys (map-key-as-secret per OQ-12), this is the map key itself.
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/keys/proxyKey1",
                "reveal_secrets=true", "", "authorization", "security-admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("proxyKey1", body.get("name").asText());
        // The secret is masked or unmasked depending on the in-memory Key.key value populated by
        // ConfigPostProcessor — at minimum it must NOT be the masking sentinel "***".
        assertFalse("***".equals(body.get("key").asText()),
                () -> "Secret must be unmasked with reveal_secrets=true: " + response.body());
    }

    @Test
    void testRevealSecretsRequiresSecurityAdmin() {
        // Plain admin asking for reveal_secrets gets 403 — same gate that protects per-entity GET.
        // For the keys path the security-admin check fires first (keys requires security-admin),
        // so use models to exercise the reveal_secrets gate independently.
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models/test-model-v1",
                "reveal_secrets=true", "", "authorization", "admin");
        verify(response, 403);
    }

    @Test
    void testUnknownTypeReturns404() {
        // The RouteTemplate regex only matches the supported types, so an unknown type doesn't
        // match the route at all — the dispatcher falls through and returns 404.
        verify(send(HttpMethod.GET, "/v1/admin/config/file/unknown-type", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testSettingsListReturnsSingletonRow() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/settings", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode items = ProxyUtil.MAPPER.readTree(response.body()).get("items");
        assertEquals(1, items.size());
        assertEquals("global", items.get(0).get("name").asText());
    }

    @Test
    @SneakyThrows
    void testSettingsGetReturnsFileDefaultValues() {
        // Default test config does not populate globalInterceptors — the file/default projection
        // returns empty arrays, not 404 (the file-config surface always projects).
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/settings/global", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("global", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertTrue(body.get("globalInterceptors").isArray());
        assertTrue(body.get("retriableErrorCodes").isArray());
        assertFalse(body.has("source"),
                () -> "U.1: source field must not appear in any response: " + response.body());
    }

    @Test
    void testWriteVerbsNotAllowed() {
        // POST / PUT / DELETE are wired through the router to FileConfigController, which emits
        // 405 + Allow: GET (RFC 9110 §15.5.6). The earlier "404 OR 405" accept-band let the
        // surface drift to fall-through into GlobalRouteController, where a configured custom
        // route could match unintentionally — pin to 405 explicitly.
        for (HttpMethod method : new HttpMethod[]{HttpMethod.PUT, HttpMethod.POST, HttpMethod.DELETE}) {
            Response resp = send(method, "/v1/admin/config/file/models/test-model-v1", null, "{}",
                    "authorization", "admin");
            assertEquals(405, resp.status(),
                    () -> "Expected 405 for " + method + ", got " + resp.status());
            assertEquals("GET", resp.headers().get("Allow"),
                    () -> "Expected Allow: GET for " + method + ", got " + resp.headers().get("Allow"));
        }
    }

    @Test
    @SneakyThrows
    void testApiManagedEntriesAreNotSurfacedOnFileEndpoint() {
        // Even if an API blob exists, the file-config listing must exclude it. We don't put a blob
        // here — we just verify the listing only contains simple-name keys.
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode items = ProxyUtil.MAPPER.readTree(response.body()).get("items");
        for (JsonNode item : items) {
            String name = item.get("name").asText();
            assertFalse(name.contains("/"),
                    () -> "Canonical-ID-shaped entry leaked into file listing: " + name);
        }
    }
}
