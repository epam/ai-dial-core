package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

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
        // Binding valid + admin role passes gate; stub returns 405 to signal "no handler yet".
        Response response = send(HttpMethod.GET, "/v1/interceptors/platform/anything", null, "",
                "authorization", "admin");
        verify(response, 405);
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
        Response response = send(HttpMethod.PUT, "/v1/models/public/gpt-4", null, "{}",
                "authorization", "admin");
        verify(response, 405);
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
}
