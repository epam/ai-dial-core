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
    void testAuthenticatedNonAdminCanReadPublicEntity() {
        // public/ reads are open to any authenticated caller; the 1S.1 read path returns the model body.
        Response response = send(HttpMethod.GET, "/v1/models/public/gpt-4", null, "",
                "authorization", "user");
        verify(response, 200);
    }

    @Test
    void testNonAdminCannotWritePublicEntity() {
        Response response = send(HttpMethod.PUT, "/v1/models/public/gpt-4", null, "{}",
                "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testAdminCanWritePublicEntity() {
        // PUT against a name not present in the API store returns 404 — slice 2S.11 enforces strict-split
        // semantics (PUT requires existing API entity; gpt-4 here is a file-defined entry, not an API
        // entry, so PUT is not an in-place upsert).
        Response response = send(HttpMethod.PUT, "/v1/models/public/non-existent-name", null, "{}",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testApiKeyWithDefaultRoleCanReadPublic() {
        // Default api-key proxyKey1 (role: "default") authenticates but is not admin — public reads
        // are open to any authenticated caller, so the 1S.1 read path returns the model body.
        Response response = send(HttpMethod.GET, "/v1/models/public/gpt-4");
        verify(response, 200);
    }

    @Test
    void testApiKeyWithDefaultRoleCannotReadPlatform() {
        // Default api-key proxyKey1 is authenticated but lacks admin — platform reads require admin.
        Response response = send(HttpMethod.GET, "/v1/interceptors/platform/anything");
        verify(response, 403);
    }

    @Test
    void testApiKeyWithDefaultRoleCannotReadPlatformRoles() {
        // Same gate as testApiKeyWithDefaultRoleCannotReadPlatform; this asserts it specifically
        // for /v1/roles/platform/ since that is the endpoint operators most often probe first.
        Response response = send(HttpMethod.GET, "/v1/roles/platform/");
        verify(response, 403);
    }

    @Test
    void testUnknownApiKeyIsRejected() {
        // Unknown key fails in ApiKeyStore before authz — 401 (not 403); proves the gate cannot
        // be probed by guessing keys.
        Response response = send(HttpMethod.GET, "/v1/roles/platform/", null, "",
                "api-key", "no-such-key-exists");
        verify(response, 401);
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/admin-api-key-config.json")
    void testApiKeyWithAdminRoleCanReadPlatform() {
        // Sibling tests gate admin via the JWT mock (authorization: "admin"); this is the only
        // coverage of the api-key path — Key.roles=["admin"] flows through getMergedRoles to
        // context.userRoles and matches access.admin.rules (CONTAIN, target="admin").
        Response response = send(HttpMethod.GET, "/v1/roles/platform/", null, "",
                "api-key", "adminKey1");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("roles", body.get("entityType").asText());
        assertEquals("platform", body.get("bucket").asText());
        JsonNode items = body.get("items");
        assertTrue(items.isArray() && !items.isEmpty(),
                () -> "items must include the admin role from the fixture: " + response.body());
    }
}
