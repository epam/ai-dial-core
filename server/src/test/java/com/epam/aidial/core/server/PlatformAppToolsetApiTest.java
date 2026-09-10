package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for applications/toolsets materialized into the {@code platform} bucket.
 * Covers PUT/GET/DELETE round-trip via {@code ConfigResourceController}, userRoles survival,
 * function-type-app rejection, that decrypted auth_settings/external-service secrets
 * held in the merged {@code Config} never leak on GET, and that GET computes the per-user
 * sign-in statuses for toolset auth settings and application external services.
 */
public class PlatformAppToolsetApiTest extends ResourceBaseTest {

    private static final String APP_BODY = """
            {
              "endpoint": "http://application1/v1/completions",
              "display_name": "Platform App"
            }
            """;

    private static final String TOOLSET_BODY = """
            {
              "endpoint": "http://localhost:9876",
              "transport": "HTTP",
              "display_name": "Platform Toolset"
            }
            """;

    @Test
    void testApplicationPutGetDeleteRoundTrip() {
        Response put = send(HttpMethod.PUT, "/v1/applications/platform/my-platform-app", null, APP_BODY,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));

        Response get = send(HttpMethod.GET, "/v1/applications/platform/my-platform-app", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"my-platform-app\""),
                () -> "Expected short name in body: " + get.body());
        assertTrue(get.body().contains("\"endpoint\":\"http://application1/v1/completions\""),
                () -> "Expected endpoint in body: " + get.body());

        Response del = send(HttpMethod.DELETE, "/v1/applications/platform/my-platform-app", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response getAfterDelete = send(HttpMethod.GET, "/v1/applications/platform/my-platform-app", null, "",
                "authorization", "admin");
        verify(getAfterDelete, 404);
    }

    @Test
    void testToolSetPutGetDeleteRoundTrip() {
        Response put = send(HttpMethod.PUT, "/v1/toolsets/platform/my-platform-toolset", null, TOOLSET_BODY,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));

        Response get = send(HttpMethod.GET, "/v1/toolsets/platform/my-platform-toolset", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"my-platform-toolset\""),
                () -> "Expected short name in body: " + get.body());
        assertTrue(get.body().contains("\"endpoint\":\"http://localhost:9876\""),
                () -> "Expected endpoint in body: " + get.body());

        Response del = send(HttpMethod.DELETE, "/v1/toolsets/platform/my-platform-toolset", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response getAfterDelete = send(HttpMethod.GET, "/v1/toolsets/platform/my-platform-toolset", null, "",
                "authorization", "admin");
        verify(getAfterDelete, 404);
    }

    @Test
    void testInvalidToolSetNameRejectedOnWrite() {
        // '.' passes ENTITY_NAME_PATTERN but fails the rebuild's isValidToolSetKey (RESOURCE_KEY_PATTERN),
        // so the write must be rejected up front instead of creating a blob that serves until the next
        // rebuild and then vanishes (200-on-PUT / 404-on-GET orphan).
        Response put = send(HttpMethod.PUT, "/v1/toolsets/platform/my.toolset", null, TOOLSET_BODY,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 400);

        // DELETE validates the name too, consistent with the adjacent ENTITY_NAME_PATTERN gate.
        Response del = send(HttpMethod.DELETE, "/v1/toolsets/platform/my.toolset", null, "",
                "authorization", "admin");
        verify(del, 400);
    }

    @Test
    void testApplicationPut403ForNonAdmin() {
        Response put = send(HttpMethod.PUT, "/v1/applications/platform/no-admin-app", null, APP_BODY,
                "authorization", "user");
        verify(put, 403);
    }

    @Test
    void testToolSetPut403ForNonAdmin() {
        Response put = send(HttpMethod.PUT, "/v1/toolsets/platform/no-admin-toolset", null, TOOLSET_BODY,
                "authorization", "user");
        verify(put, 403);
    }

    @Test
    void testApplicationUserRolesSurvivePut() {
        // platform apps are admin-managed config equivalents whose access model IS
        // userRoles — unlike public-bucket user-published apps, which must not self-grant it.
        String body = """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Restricted Platform App",
                  "user_roles": ["role-a", "role-b"]
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/applications/platform/restricted-app", null, body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);

        Response get = send(HttpMethod.GET, "/v1/applications/platform/restricted-app", null, "",
                "authorization", "admin");
        verify(get, 200);
        // userRoles is a Set — assert both roles are present without depending on serialized order.
        assertTrue(get.body().contains("\"role-a\"") && get.body().contains("\"role-b\""),
                () -> "Expected user_roles to survive the write on platform bucket: " + get.body());
        assertFalse(get.body().contains("\"user_roles\":[]"),
                () -> "user_roles must not be wiped on platform bucket: " + get.body());
    }

    @Test
    void testToolSetUserRolesSurvivePut() {
        // Same access-model rationale as testApplicationUserRolesSurvivePut, but for toolsets.
        String body = """
                {
                  "endpoint": "http://localhost:9876",
                  "transport": "HTTP",
                  "display_name": "Restricted Platform Toolset",
                  "user_roles": ["role-a", "role-b"]
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/toolsets/platform/restricted-toolset", null, body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);

        Response get = send(HttpMethod.GET, "/v1/toolsets/platform/restricted-toolset", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"role-a\"") && get.body().contains("\"role-b\""),
                () -> "Expected user_roles to survive the write on platform bucket: " + get.body());
        assertFalse(get.body().contains("\"user_roles\":[]"),
                () -> "user_roles must not be wiped on platform bucket: " + get.body());
    }

    @Test
    void testApplicationExternalServiceSecretNeverLeaksOnGet() {
        String body = """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Secret-bearing App",
                  "external_services": {
                    "svc1": {
                      "display_name": "Salesforce",
                      "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "my-client-id",
                        "client_secret": "my-client-secret",
                        "redirect_uri": "http://localhost:3000/auth/signin",
                        "authorization_endpoint": "https://static.auth.example.com/authorize",
                        "token_endpoint": "https://static.auth.example.com/token"
                      }
                    }
                  }
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/applications/platform/secret-app", null, body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);

        Response get = send(HttpMethod.GET, "/v1/applications/platform/secret-app", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertFalse(get.body().contains("my-client-secret"),
                () -> "Plaintext client_secret must never appear on GET: " + get.body());
        assertFalse(get.body().contains("\"client_secret\""),
                () -> "client_secret field must be absent from GET response: " + get.body());
        // Pin the value, not just the absence of the secret: this read is blob-sourced and deserializeBlob does
        // not decrypt, so without decryptExternalServiceSecretsForResponse the hint would be the tail of the
        // base64 ciphertext — present and plausible, and invisible to an absence-only assertion.
        assertTrue(get.body().contains("\"client_secret_hint\":\"cret\""),
                () -> "hint must be derived from the plaintext secret: " + get.body());
    }

    @Test
    void testPlatformAppExternalServiceAccessAllowedByUserRoles() {
        // Regression (#1773 review): external-service access on a platform app must go through the
        // app's userRoles like a config app, not folder rules. This app is open (no user_roles), so a
        // non-admin user can sign in; before the fix the folder-rules branch returned 403.
        String body = """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Ext-Svc Platform App",
                  "external_services": {
                    "svc1": {
                      "display_name": "Salesforce",
                      "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid",
                        "client_secret": "csecret-value",
                        "redirect_uri": "http://localhost:3000/auth/signin",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876"
                      }
                    }
                  }
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/applications/platform/extsvc-open-app", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);

        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody("{\"access_token\":\"t\",\"refresh_token\":\"r\",\"expires_in\":3600}")
                .setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "applications/extsvc-open-app/external_services/svc1",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "user");
            verify(signIn, 200, "true");
        }
    }

    @Test
    void testPlatformAppExternalServiceDeniedWithoutUserRole() {
        // Denial still works via the userRoles branch: a user lacking the app's user_roles is 403, and
        // verifyAccess short-circuits before any OAuth call (no mock server needed).
        String body = """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Restricted Ext-Svc App",
                  "user_roles": ["role-the-user-lacks"],
                  "external_services": {
                    "svc1": {
                      "display_name": "Salesforce",
                      "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid",
                        "client_secret": "csecret-value",
                        "redirect_uri": "http://localhost:3000/auth/signin",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876"
                      }
                    }
                  }
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/applications/platform/extsvc-restricted-app", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "applications/extsvc-restricted-app/external_services/svc1",
                    "credentials_level": "USER",
                    "authentication_type": "OAUTH",
                    "code": "auth-code"
                }
                """, "authorization", "user");
        assertEquals(403, signIn.status());
    }

    @Test
    void testToolSetAuthSettingsSecretNeverLeaksOnGet() {
        String body = """
                {
                  "endpoint": "http://localhost:9876",
                  "transport": "HTTP",
                  "display_name": "Secret-bearing Toolset",
                  "auth_settings": {
                    "authentication_type": "OAUTH",
                    "client_id": "my-client-id",
                    "client_secret": "my-client-secret",
                    "redirect_uri": "http://localhost:3000/auth/signin",
                    "authorization_endpoint": "https://static.auth.example.com/authorize",
                    "token_endpoint": "https://static.auth.example.com/token"
                  }
                }
                """;
        try (TestWebServer ignore = new TestWebServer(9876)) {
            Response put = send(HttpMethod.PUT, "/v1/toolsets/platform/secret-toolset", null, body,
                    "authorization", "admin", "If-None-Match", "*");
            verify(put, 200);

            Response get = send(HttpMethod.GET, "/v1/toolsets/platform/secret-toolset", null, "",
                    "authorization", "admin");
            verify(get, 200);
            assertFalse(get.body().contains("my-client-secret"),
                    () -> "Plaintext client_secret must never appear on GET: " + get.body());
            assertFalse(get.body().contains("\"client_secret\""),
                    () -> "client_secret field must be absent from GET response: " + get.body());
        }
    }

    @Test
    void testMetadataListingIncludesPlatformApplication() {
        verify(send(HttpMethod.PUT, "/v1/applications/platform/listed-app", null, APP_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response metadata = send(HttpMethod.GET, "/v1/metadata/applications/platform/", null, "",
                "authorization", "admin");
        verify(metadata, 200);
        assertTrue(metadata.body().contains("listed-app"),
                () -> "Expected listed-app in platform applications listing: " + metadata.body());
    }

    @Test
    void testMetadataListingIncludesPlatformToolSet() {
        verify(send(HttpMethod.PUT, "/v1/toolsets/platform/listed-toolset", null, TOOLSET_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response metadata = send(HttpMethod.GET, "/v1/metadata/toolsets/platform/", null, "",
                "authorization", "admin");
        verify(metadata, 200);
        assertTrue(metadata.body().contains("listed-toolset"),
                () -> "Expected listed-toolset in platform toolsets listing: " + metadata.body());
    }

    /**
     * The hint on the config API's own read path, which redacts a JSON projection of the merged config rather
     * than a {@link com.epam.aidial.core.config.ResourceAuthSettings} object. Also covers the GET → PUT round
     * trip: a client that echoes the response back, hint included, must not displace the stored secret.
     *
     * <p>Note this cannot observe {@code @JsonProperty(READ_ONLY)} itself. {@code redactSecretFields} strips
     * {@code client_secret_hint} from the projection and re-derives it from the stored secret, so a hint that
     * did get bound and persisted would still never surface here — that binding is covered by
     * {@code ResourceAuthSettingsTest.testHintIsSerializedButNeverAcceptedFromClients}.</p>
     */
    @Test
    void testPlatformToolsetHintOnReadAndRoundTrip() {
        String secret = "platform-toolset-secret-9c4f";
        String body = """
                {
                  "endpoint": "http://localhost:9876/mcp",
                  "transport": "HTTP",
                  "display_name": "Hint Toolset",
                  "auth_settings": {
                    "authentication_type": "OAUTH",
                    "client_id": "cid",
                    "client_secret": "%s",
                    "redirect_uri": "http://localhost:3000/auth/signin",
                    "authorization_endpoint": "http://localhost:9876/authorize",
                    "token_endpoint": "http://localhost:9876"
                  }
                }
                """.formatted(secret);
        // Echo of the GET response: client_secret absent (meaning "keep"), hint echoed back as a client would.
        String echoed = """
                {
                  "endpoint": "http://localhost:9876/mcp",
                  "transport": "HTTP",
                  "display_name": "Hint Toolset",
                  "auth_settings": {
                    "authentication_type": "OAUTH",
                    "client_id": "cid",
                    "client_secret_hint": "beef",
                    "redirect_uri": "http://localhost:3000/auth/signin",
                    "authorization_endpoint": "http://localhost:9876/authorize",
                    "token_endpoint": "http://localhost:9876"
                  }
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");

            verify(send(HttpMethod.PUT, "/v1/toolsets/platform/hint-toolset", null, body,
                    "authorization", "admin", "If-None-Match", "*"), 200);

            Response get = send(HttpMethod.GET, "/v1/toolsets/platform/hint-toolset", null, "", "authorization", "admin");
            verify(get, 200);
            assertTrue(get.body().contains("\"client_secret_hint\":\"9c4f\""),
                    () -> "admin must see the hint: " + get.body());
            assertFalse(get.body().contains(secret), () -> "secret leaked: " + get.body());

            verify(send(HttpMethod.PUT, "/v1/toolsets/platform/hint-toolset", null, echoed, "authorization", "admin"), 200);

            // The stored secret is untouched, so the hint is still derived from it and never from "beef".
            Response reread = send(HttpMethod.GET, "/v1/toolsets/platform/hint-toolset", null, "", "authorization", "admin");
            verify(reread, 200);
            assertTrue(reread.body().contains("\"client_secret_hint\":\"9c4f\""),
                    () -> "round-tripped PUT must not overwrite the stored secret: " + reread.body());
            assertFalse(reread.body().contains("beef"),
                    () -> "hint must be re-derived from the stored secret, not echoed: " + reread.body());
            assertFalse(reread.body().contains(secret), () -> "secret leaked: " + reread.body());
        }
    }

    /**
     * The config API read must surface the same per-user sign-in statuses as {@code /openai/toolsets}
     * and the generic resource GET. API_KEY auth keeps the test hermetic — no token endpoint to mock.
     */
    @Test
    void testPlatformToolSetAuthStatusesOnGet() {
        String body = """
                {
                  "endpoint": "http://localhost:9876",
                  "transport": "HTTP",
                  "display_name": "Status Toolset",
                  "auth_settings": {
                    "authentication_type": "API_KEY",
                    "api_key_header": "Authorization"
                  }
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/toolsets/platform/status-toolset", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response before = send(HttpMethod.GET, "/v1/toolsets/platform/status-toolset", null, "",
                "authorization", "admin");
        verify(before, 200);
        assertTrue(before.body().contains("\"global_auth_status\":\"SIGNED_OUT\""),
                () -> "expected global_auth_status on platform toolset GET: " + before.body());
        assertTrue(before.body().contains("\"user_level_auth_status\":\"SIGNED_OUT\""),
                () -> "expected user_level_auth_status on platform toolset GET: " + before.body());
        assertFalse(before.body().contains("\"client_secret\""),
                () -> "client_secret must stay absent: " + before.body());

        // Short-name url — the form sign-in normalizes for platform deployments; it pins the scope
        // the GET-side enrichment has to read from.
        verify(send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "status-toolset",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """, "authorization", "admin"), 200, "true");
        verify(send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "status-toolset",
                    "credentialsLevel": "USER",
                    "authenticationType": "API_KEY",
                    "api_key": "Bearer api_key"
                }
                """, "authorization", "admin"), 200, "true");

        Response after = send(HttpMethod.GET, "/v1/toolsets/platform/status-toolset", null, "",
                "authorization", "admin");
        verify(after, 200);
        assertTrue(after.body().contains("\"global_auth_status\":\"SIGNED_IN\""),
                () -> "global_auth_status must flip after GLOBAL sign-in: " + after.body());
        assertTrue(after.body().contains("\"user_level_auth_status\":\"SIGNED_IN\""),
                () -> "user_level_auth_status must flip after USER sign-in: " + after.body());
    }

    @Test
    void testPlatformApplicationExternalServiceAuthStatusesOnGet() {
        String body = """
                {
                  "endpoint": "http://application1/v1/completions",
                  "display_name": "Status App",
                  "external_services": {
                    "apikey-svc": {
                      "display_name": "Billing",
                      "auth_settings": {
                        "authentication_type": "API_KEY",
                        "api_key_header": "Authorization"
                      }
                    }
                  }
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/applications/platform/status-app", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response before = send(HttpMethod.GET, "/v1/applications/platform/status-app", null, "",
                "authorization", "admin");
        verify(before, 200);
        assertTrue(before.body().contains("\"user_level_auth_status\":\"SIGNED_OUT\""),
                () -> "expected user_level_auth_status on platform app GET: " + before.body());
        assertTrue(before.body().contains("\"app_level_auth_status\":\"SIGNED_OUT\""),
                () -> "expected app_level_auth_status on platform app GET: " + before.body());
        assertFalse(before.body().contains("\"client_secret\""),
                () -> "client_secret must stay absent: " + before.body());

        // Short-name scope — the same form /v1/ops/external-service/signin normalizes for platform apps.
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "applications/status-app/external_services/apikey-svc",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "k"
                }
                """, "authorization", "admin"), 200, "true");
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "applications/status-app/external_services/apikey-svc",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "k"
                }
                """, "authorization", "admin"), 200, "true");

        Response after = send(HttpMethod.GET, "/v1/applications/platform/status-app", null, "",
                "authorization", "admin");
        verify(after, 200);
        assertTrue(after.body().contains("\"user_level_auth_status\":\"SIGNED_IN\""),
                () -> "user_level_auth_status must flip after USER sign-in: " + after.body());
        assertTrue(after.body().contains("\"app_level_auth_status\":\"SIGNED_IN\""),
                () -> "app_level_auth_status must flip after APPLICATION sign-in: " + after.body());
    }

    /**
     * Statuses and the admin hint are not mutually exclusive: the same GET response must carry both,
     * and the sign-in flow behind the status must not disturb the stored secret the hint derives from.
     */
    @Test
    void testPlatformToolSetStatusesAndHintTogether() {
        String secret = "platform-toolset-secret-9c4f";
        String body = """
                {
                  "endpoint": "http://localhost:9876/mcp",
                  "transport": "HTTP",
                  "display_name": "Hint Status Toolset",
                  "auth_settings": {
                    "authentication_type": "OAUTH",
                    "client_id": "cid",
                    "client_secret": "%s",
                    "redirect_uri": "http://localhost:3000/auth/signin",
                    "authorization_endpoint": "http://localhost:9876/authorize",
                    "token_endpoint": "http://localhost:9876/token"
                  }
                }
                """.formatted(secret);

        // The OAUTH sign-in drives protected-resource and authorization-server discovery against the
        // toolset endpoint before redeeming the code, so each well-known route is mapped explicitly.
        String protectedResourceMetadata = """
                {
                    "resource": "http://localhost:9876/mcp",
                    "authorization_servers": ["http://localhost:9876"]
                }
                """;
        String authServerMetadata = """
                {
                    "issuer": "http://localhost:9876",
                    "authorization_endpoint": "http://localhost:9876/authorize",
                    "token_endpoint": "http://localhost:9876/token",
                    "code_challenge_methods_supported": ["S256"]
                }
                """;
        String tokenResponse = """
                {
                    "access_token": "t",
                    "refresh_token": "r",
                    "expires_in": 3600
                }
                """;
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp",
                    200, protectedResourceMetadata, "Content-Type", "application/json");
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server",
                    200, authServerMetadata, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token", 200, tokenResponse, "Content-Type", "application/json");
            verify(send(HttpMethod.PUT, "/v1/toolsets/platform/hint-status-toolset", null, body,
                    "authorization", "admin", "If-None-Match", "*"), 200);

            Response get = send(HttpMethod.GET, "/v1/toolsets/platform/hint-status-toolset", null, "",
                    "authorization", "admin");
            verify(get, 200);
            assertTrue(get.body().contains("\"client_secret_hint\":\"9c4f\""),
                    () -> "admin must see the hint: " + get.body());
            assertTrue(get.body().contains("\"user_level_auth_status\":\"SIGNED_OUT\""),
                    () -> "expected user_level_auth_status alongside the hint: " + get.body());
            assertFalse(get.body().contains(secret), () -> "secret leaked: " + get.body());

            verify(send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "hint-status-toolset",
                        "credentialsLevel": "USER",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """, "authorization", "admin"), 200, "true");

            Response after = send(HttpMethod.GET, "/v1/toolsets/platform/hint-status-toolset", null, "",
                    "authorization", "admin");
            verify(after, 200);
            assertTrue(after.body().contains("\"user_level_auth_status\":\"SIGNED_IN\""),
                    () -> "user_level_auth_status must flip after USER sign-in: " + after.body());
            assertTrue(after.body().contains("\"client_secret_hint\":\"9c4f\""),
                    () -> "hint must survive the sign-in round trip: " + after.body());
            assertFalse(after.body().contains(secret), () -> "secret leaked: " + after.body());
        }
    }

    @Test
    void testFunctionTypeApplicationRejectedOnPlatform() {
        String body = """
                {
                  "display_name": "Function App",
                  "function": {
                    "runtime": "python3.11",
                    "source_folder": "files/EPM-RTC-GPT/code/",
                    "mapping": {"chat_completion": "/application"}
                  }
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/applications/platform/function-app", null, body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 400);
    }
}
