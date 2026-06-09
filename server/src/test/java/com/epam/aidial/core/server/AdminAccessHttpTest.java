package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

/**
 * HTTP integration coverage for admin access through {@link
 * com.epam.aidial.core.server.controller.AccessControlBaseController}-served routes (FILES,
 * RESOURCE). Asserts the behavior produced by the {@code getAdminAccess} permission rule in
 * {@link com.epam.aidial.core.server.security.AccessService}: admin holds {@code ALL} permissions
 * on {@code public/} resources of any type; admin requests on user buckets fall through to the
 * standard rules path (owner/share-based grants apply, no admin override).
 */
public class AdminAccessHttpTest extends ResourceBaseTest {

    @Test
    void testAdminWritesPublicApplication() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/admin-write-app", null, """
                {
                  "endpoint": "http://example.com/v1/completions",
                  "display_name": "Admin Write App"
                }
                """, "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/applications/public/admin-write-app", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    void testAdminUserBucketRequestsDeniedWithoutShareOrOwnership() {
        // Owner user creates an application in their bucket.
        verify(send(HttpMethod.PUT,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/owner-app", null, """
                {
                  "endpoint": "http://owner.example.com/v1/completions",
                  "display_name": "Owner App"
                }
                """, "Api-key", "proxyKey1"), 200);

        // Admin without a publication-share grant gets 403 — the admin rule only admits public/,
        // review buckets, and public-application source dirs; user buckets fall to owner/share
        // rules. PublicationApiTest covers the share-grant path.
        verify(send(HttpMethod.GET,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/owner-app", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testAdminWritesPublicPrompts() {
        // OQ-21: admin manages shared prompts in public/. Admin gets ALL via getAdminAccess.
        verify(send(HttpMethod.PUT, "/v1/prompts/public/admin-prompt", null, PROMPT_BODY,
                "authorization", "admin"), 200);
    }

    @Test
    void testAdminFilesPublicReadAdmittedUserBucketDenied() {
        // Admin GET on public/files — 404 (not 403) confirms admin was admitted; the file just
        // doesn't exist.
        verify(send(HttpMethod.GET, "/v1/files/public/no-such-file.txt", null, "",
                "authorization", "admin"), 404);

        // Admin GET on a user bucket — 403, no share/ownership grant.
        verify(send(HttpMethod.GET,
                "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/no-such-file.txt", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testNonAdminPublicRead() {
        verify(send(HttpMethod.PUT, "/v1/applications/public/for-user-read", null, """
                {
                  "endpoint": "http://internal/v1/completions",
                  "display_name": "Shared"
                }
                """, "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/applications/public/for-user-read", null, "",
                "authorization", "user"), 200);
    }
}
