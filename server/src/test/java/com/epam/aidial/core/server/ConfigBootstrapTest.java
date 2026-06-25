package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for the slice 1S.0 bootstrap: end-to-end exercise of the
 * CONFIG_RESOURCE route, EntityBucketBinding allowlist, and AdminRoleAuthorizationService
 * dispatch through the full Vert.x stack. Pattern mirrors {@link ResourceApiTest}.
 */
public class ConfigBootstrapTest extends ResourceBaseTest {

    @Test
    void testBindingMismatchReturnsNotFound() {
        // interceptors only live in platform/ — public/ is rejected as binding mismatch.
        Response response = send(HttpMethod.GET, "/v1/interceptors/public/foo", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testNonAdminCannotReadPlatformEntity() {
        Response response = send(HttpMethod.GET, "/v1/interceptors/platform/anything", null, "",
                "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testAdminCanReachPlatformEntity() {
        // Binding valid + admin role passes gate; the 1S.3 interceptors read handler responds 404
        // for an unknown name. Either 404 (handler reached) or 405 (stub still in place for a type)
        // proves the gate admitted — both are distinct from the 403 / bucket-mismatch 404 paths.
        Response response = send(HttpMethod.GET, "/v1/interceptors/platform/anything", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testAuthenticatedNonAdminPassesAuthGateForPublicBucketGet() {
        // public/ reads are open to any authenticated caller. Applications remain in the public
        // bucket. 404 (not 401/403) confirms the auth gate admitted the caller and only the
        // entity lookup failed.
        Response response = send(HttpMethod.GET, "/v1/applications/public/non-existent-app", null, "",
                "authorization", "user");
        verify(response, 404);
    }

    @Test
    void testNonAdminCannotWritePublicEntity() {
        Response response = send(HttpMethod.PUT, "/v1/applications/public/some-app", null, "{}",
                "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testAdminCanWritePlatformModel() {
        // PUT is upsert. Bare PUT against a non-existent model creates it —
        // an empty body deserializes to a Model with no upstreams; the write succeeds (200).
        Response response = send(HttpMethod.PUT, "/v1/models/platform/non-existent-name", null, "{}",
                "authorization", "admin");
        verify(response, 200);
    }

    @Test
    void testApiKeyWithDefaultRolePassesAuthGateForPublicBucketGet() {
        // Default api-key proxyKey1 (role: "default") authenticates but is not admin — public reads
        // are open to any authenticated caller. Applications remain in the public bucket.
        // 404 (not 401) confirms the auth gate admitted the key and only the entity lookup failed.
        Response response = send(HttpMethod.GET, "/v1/applications/public/non-existent-app");
        verify(response, 404);
    }

    @Test
    void testApiKeyWithDefaultRoleCannotReadPlatform() {
        // Default api-key proxyKey1 is authenticated but lacks admin — platform reads require admin.
        Response response = send(HttpMethod.GET, "/v1/interceptors/platform/anything");
        verify(response, 403);
    }

    @Test
    void testApiKeyWithDefaultRoleCannotReadPlatformModel() {
        // Models are now in the platform bucket — platform reads require admin regardless of entity type.
        Response response = send(HttpMethod.GET, "/v1/models/platform/anything");
        verify(response, 403);
    }

    @Test
    void testApiKeyWithDefaultRoleCannotReadPlatformRoles() {
        // U.0: listing route moved to /v1/metadata/...; same admin-only gate applies.
        Response response = send(HttpMethod.GET, "/v1/metadata/roles/platform/");
        verify(response, 403);
    }

    @Test
    void testUnknownApiKeyIsRejected() {
        // Unknown key fails in ApiKeyStore before authz — 401 (not 403); proves the gate cannot
        // be probed by guessing keys.
        Response response = send(HttpMethod.GET, "/v1/metadata/roles/platform/", null, "",
                "api-key", "no-such-key-exists");
        verify(response, 401);
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/admin-api-key-config.json")
    void testApiKeyWithAdminRoleCanReadPlatformMetadata() {
        // Sibling tests gate admin via the JWT mock (authorization: "admin"); this is the only
        // coverage of the api-key path — Key.roles=["admin"] flows through getMergedRoles to
        // context.userRoles and matches access.admin.rules (CONTAIN, target="admin").
        // U.0: metadata listing is blob-only; with only file-defined roles, expect FOLDER+empty
        // items or 404. The point of this test is to assert the gate admits the api-key.
        Response response = send(HttpMethod.GET, "/v1/metadata/roles/platform/", null, "",
                "api-key", "adminKey1");
        if (response.status() == 200) {
            JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals("FOLDER", body.get("nodeType").asText());
            assertTrue(body.has("items"), () -> "Expected items array: " + response.body());
        } else {
            verify(response, 404);
        }
    }
}
