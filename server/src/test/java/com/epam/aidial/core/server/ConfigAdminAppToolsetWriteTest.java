package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

/**
 * HTTP integration tests for slice 3S.3: admin write paths for {@code applications} and
 * {@code toolsets} in {@code public/}. The production code (admin authz preflight on
 * {@code AccessControlBaseController}) ships with 1S.5; this slice fills the integration-test
 * surface that 1S.4 / 1S.5 left partial — adds applications full-lifecycle (PUT → GET →
 * DELETE → 404), toolsets DELETE, and 403 coverage on non-admin writes.
 *
 * <p>Per design 02 §6 applications/toolsets stay blob-native (not in
 * {@code MergedConfigStore.MANAGED_TYPES}); admin writes go through the same
 * {@link com.epam.aidial.core.server.controller.ResourceController} path as user-published
 * writes. No new code; structurally the test verifies the unification.
 */
public class ConfigAdminAppToolsetWriteTest extends ResourceBaseTest {

    @Test
    void testAdminApplicationFullLifecycle() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/admin-app-cycle", null, """
                {
                  "endpoint": "http://example.com/v1/completions",
                  "display_name": "Cycle App"
                }
                """, "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/applications/public/admin-app-cycle", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/applications/public/admin-app-cycle", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/applications/public/admin-app-cycle", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testAdminToolsetFullLifecycle() {
        verify(send(HttpMethod.PUT, "/v1/toolsets/public/admin-toolset-cycle", null, """
                {
                  "transport": "http",
                  "endpoint": "http://localhost:9876",
                  "display_name": "Cycle Toolset"
                }
                """, "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/toolsets/public/admin-toolset-cycle", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/toolsets/public/admin-toolset-cycle", null, "",
                "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/toolsets/public/admin-toolset-cycle", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testNonAdminCannotWritePublicApplication() {
        // Non-admin authenticated caller: preflight does NOT admit; existing rules-based
        // AccessService denies public writes. Response code may be 403 (forbidden) — confirms
        // the admin write path is gated on the admin role.
        Response response = send(HttpMethod.PUT, "/v1/applications/public/should-not-create", null, """
                {
                  "endpoint": "http://example.com/v1/completions",
                  "display_name": "Should Not Create"
                }
                """, "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testNonAdminCannotWritePublicToolset() {
        Response response = send(HttpMethod.PUT, "/v1/toolsets/public/should-not-create", null, """
                {
                  "transport": "http",
                  "endpoint": "http://localhost:9876",
                  "display_name": "Should Not Create"
                }
                """, "authorization", "user");
        verify(response, 403);
    }
}
