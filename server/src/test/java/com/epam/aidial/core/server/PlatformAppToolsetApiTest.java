package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for applications/toolsets materialized into the {@code platform} bucket.
 * Covers PUT/GET/DELETE round-trip via {@code ConfigResourceController}, userRoles survival,
 * function-type-app rejection, and that decrypted auth_settings/external-service secrets
 * held in the merged {@code Config} never leak on GET.
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
        assertTrue(get.body().contains("\"name\":\"applications/platform/my-platform-app\""),
                () -> "Expected canonical name in body: " + get.body());
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
        assertTrue(get.body().contains("\"name\":\"toolsets/platform/my-platform-toolset\""),
                () -> "Expected canonical name in body: " + get.body());
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
