package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;


/**
 * Fail-closed coverage for the Configuration/Admin API surface (finding #1): when
 * {@code access.admin.rules} is empty/unconfigured, {@link com.epam.aidial.core.server.security.AccessService#hasExplicitAdminAccess}
 * denies all reads and writes on the platform bucket, public writes, and every {@code /v1/admin/*}
 * endpoint — even for an otherwise-admin caller. Legacy {@link RuleMatcher} empty-rules=allow-all
 * paths are untouched.
 */
public class ConfigAdminFailClosedTest extends ResourceBaseTest {

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject()
                .put("access", new JsonObject()
                        .put("admin", new JsonObject().put("rules", new JsonArray())));
    }

    @Test
    void testAdminApplyDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, "{\"manifests\": []}",
                "authorization", "admin"), 403);
    }

    @Test
    void testAdminValidateDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, "{\"manifests\": []}",
                "authorization", "admin"), 403);
    }

    @Test
    void testAdminHealthConfigDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testAdminConfigFileListingDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.GET, "/v1/admin/config/file/models", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testPlatformKeyWriteDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.PUT, "/v1/keys/platform/k1", null, "{}",
                "authorization", "admin"), 403);
    }

    @Test
    void testPlatformKeyReadDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.GET, "/v1/keys/platform/k1", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testPlatformKeyDeleteDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.DELETE, "/v1/keys/platform/k1", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testPlatformModelWriteDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/m1", null, "{}",
                "authorization", "admin"), 403);
    }

    @Test
    void testPlatformModelReadDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.GET, "/v1/models/platform/m1", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testPlatformRolesListingDeniedWhenRulesEmpty() {
        verify(send(HttpMethod.GET, "/v1/metadata/roles/platform/", null, "",
                "authorization", "admin"), 403);
    }
}
