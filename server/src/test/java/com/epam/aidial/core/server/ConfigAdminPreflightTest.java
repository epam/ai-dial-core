package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

/**
 * HTTP integration tests for slice 1S.5: admin authz preflight in {@link
 * com.epam.aidial.core.server.controller.AccessControlBaseController} extended to FILES + RESOURCE
 * routes, both reads and writes.
 *
 * <p>Semantics (additive admit): admin acts on {@code public/} via the unified-config gate,
 * bypassing the rules-based AccessService check. The preflight does NOT admit admin onto user
 * buckets (OQ-33: gate's {@code isOwnerOf} returns false), so admin requests on user buckets fall
 * through to the existing rules-based path. Existing share-based grants (e.g. publication review)
 * continue to work via that path.
 */
public class ConfigAdminPreflightTest extends ResourceBaseTest {

    @Test
    void testAdminWritesPublicApplicationViaPreflight() {
        // Admin PUT on public/ — preflight admits regardless of rules-based config.
        verify(send(HttpMethod.PUT, "/v1/applications/public/admin-write-app", null, """
                {
                  "endpoint": "http://example.com/v1/completions",
                  "display_name": "Admin Write App"
                }
                """, "authorization", "admin"), 200);

        // Admin DELETE — preflight admits writes too.
        verify(send(HttpMethod.DELETE, "/v1/applications/public/admin-write-app", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    void testAdminUserBucketRequestsFallThroughToRulesPath() {
        // Owner user creates an application in their bucket.
        verify(send(HttpMethod.PUT,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/owner-app", null, """
                {
                  "endpoint": "http://owner.example.com/v1/completions",
                  "display_name": "Owner App"
                }
                """, "Api-key", "proxyKey1"), 200);

        // Admin GET into the user bucket — preflight does NOT admit (admin is not the bucket
        // owner). Existing rules-based AccessService runs: without an explicit share/rule for
        // admin, the read is denied. The 403 here comes from the rules path, not from the
        // preflight — a publication-share grant on the same URL would let it through (covered by
        // PublicationApiTest).
        verify(send(HttpMethod.GET,
                "/v1/applications/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/owner-app", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testAdminPreflightCoversConversationsAndPrompts() {
        // OQ-21: admin manages shared conversations/prompts in public/. The 1S.5 preflight admits
        // admin writes to these RESOURCE types; rules-based access previously had no public-write
        // rule for them.
        verify(send(HttpMethod.PUT, "/v1/prompts/public/admin-prompt", null, PROMPT_BODY,
                "authorization", "admin"), 200);
    }

    @Test
    void testAdminFilesPreflightAdmitsPublicAndDeniesUserBucket() {
        // Admin GET on public/files/non-existent — preflight ADMITS (returns 404 from
        // DownloadFileController, not 403). FILES routes go through their own controllers
        // (DownloadFileController, etc.) which inherit the preflight via AccessControlBaseController.
        verify(send(HttpMethod.GET, "/v1/files/public/no-such-file.txt", null, "",
                "authorization", "admin"), 404);

        // Admin GET on user-bucket file — preflight does NOT admit (not bucket owner); falls
        // through to rules-based path which denies in absence of an explicit share/rule.
        verify(send(HttpMethod.GET,
                "/v1/files/3CcedGxCx23EwiVbVmscVktScRyf46KypuBQ65miviST/no-such-file.txt", null, "",
                "authorization", "admin"), 403);
    }

    @Test
    void testNonAdminPublicReadStillFlowsThroughRules() {
        // Pre-populate via admin write.
        verify(send(HttpMethod.PUT, "/v1/applications/public/for-user-read", null, """
                {
                  "endpoint": "http://internal/v1/completions",
                  "display_name": "Shared"
                }
                """, "authorization", "admin"), 200);

        // Non-admin user reads — preflight inactive (not admin); rules-based AccessService grants
        // public reads to authenticated callers; sensitive endpoint stays redacted.
        verify(send(HttpMethod.GET, "/v1/applications/public/for-user-read", null, "",
                "authorization", "user"), 200);
    }
}
