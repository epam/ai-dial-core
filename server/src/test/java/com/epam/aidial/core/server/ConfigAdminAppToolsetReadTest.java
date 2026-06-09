package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * HTTP integration tests for slice 1S.4: admin reads of applications/toolsets in {@code public/}
 * routed through the {@link com.epam.aidial.core.server.security.ConfigAuthorizationService}
 * preflight (additive admit). Admin gets full-data view via the unified-config gate; non-admin
 * authenticated callers continue through the existing rules-based {@link
 * com.epam.aidial.core.server.security.AccessService} flow.
 */
public class ConfigAdminAppToolsetReadTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminReadsPublicApplicationWithFullData() {
        // Admin PUT — bootstraps a public application. Auth path is the existing rules-based one
        // for writes (1S.4 only adds the read preflight).
        Response put = send(HttpMethod.PUT, "/v1/applications/public/admin-shared-app", null, """
                {
                  "endpoint": "http://example.com/v1/completions",
                  "display_name": "Admin Shared",
                  "description": "Created via admin write"
                }
                """, "authorization", "admin");
        verify(put, 200);

        // Admin GET — preflight admits via ConfigAuthorizationService; handle() runs with
        // hasWriteAccess=true so the endpoint is NOT redacted.
        Response get = send(HttpMethod.GET, "/v1/applications/public/admin-shared-app", null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("http://example.com/v1/completions", body.get("endpoint").asText(),
                () -> "Admin must see the full endpoint via preflight admit: " + get.body());
        assertNotNull(body.get("display_name"));
    }

    @Test
    @SneakyThrows
    void testNonAdminReadOfPublicApplicationFallsThroughToExistingRules() {
        // Bootstrap as admin.
        verify(send(HttpMethod.PUT, "/v1/applications/public/shared-for-users", null, """
                {
                  "endpoint": "http://internal.example.com/v1/completions",
                  "display_name": "Shared",
                  "description": "Public read by users"
                }
                """, "authorization", "admin"), 200);

        // Non-admin user GET — preflight does NOT admit (configAuth.isAdmin == false), so the
        // existing AccessService rules-based check runs. Public reads are open to authenticated
        // callers; the response redacts the endpoint (hasWriteAccess=false from rules).
        Response get = send(HttpMethod.GET, "/v1/applications/public/shared-for-users", null, "",
                "authorization", "user");
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        // Existing redaction behaviour preserved: non-admin readers do not see internal endpoints.
        assertEquals(true, body.get("endpoint") == null || body.get("endpoint").isNull(),
                () -> "Non-admin must continue to see endpoint redacted via existing rules: " + get.body());
    }

    @Test
    @SneakyThrows
    void testAdminReadsPublicToolset() {
        verify(send(HttpMethod.PUT, "/v1/toolsets/public/admin-toolset", null, """
                {
                  "transport": "http",
                  "endpoint": "http://localhost:9876",
                  "display_name": "Admin Toolset"
                }
                """, "authorization", "admin"), 200);

        Response get = send(HttpMethod.GET, "/v1/toolsets/public/admin-toolset", null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertNotNull(body, () -> "Expected body: " + get.body());
    }

    @Test
    @SneakyThrows
    void testAdminUserBucketReadFallsThroughToExistingRules() {
        // Admin attempting to read a user-bucket application: preflight's bucket dispatch hits
        // isOwnerOf (admin is not the owner) and denies, so the existing rules-based path runs.
        // OQ-33 (admin-no-access-to-user-buckets) is locked OFF for the unified-config preflight,
        // but existing AccessService share/rules behaviour is unchanged by 1S.4.
        verify(send(HttpMethod.PUT,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/owned-app", null, """
                {
                  "endpoint": "http://owner-only.example.com/v1/completions",
                  "display_name": "Owner App"
                }
                """, "Api-key", "proxyKey1"), 200);

        // Admin tries to read it — preflight does not admit (admin is not the owner of that
        // user bucket), so existing flow decides. Without explicit shares/rules, this is denied.
        Response get = send(HttpMethod.GET,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/owned-app", null, "",
                "authorization", "admin");
        // Either 403 (no rules grant) or 200 (rules grant via shares/access.user.rules) is acceptable
        // — the assertion is that the preflight did not inadvertently admit admin onto a user bucket.
        // Asserting NOT 200-with-full-endpoint is the cleanest signal: admin must not see the owner's
        // endpoint via the preflight short-circuit.
        if (get.status() == 200) {
            JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
            // If existing rules opened the read, the response should still redact the endpoint
            // (admin is not the bucket owner; rules-based hasWriteAccess=false → redacted).
            assertEquals(true, body.get("endpoint") == null || body.get("endpoint").isNull(),
                    () -> "Admin must not see endpoint via preflight on user bucket: " + get.body());
        }
    }
}
