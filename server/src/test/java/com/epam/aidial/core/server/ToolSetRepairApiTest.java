package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.InvitationLink;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ToolSetRepairApiTest extends ResourceBaseTest {

    private static final String ADMIN_BUCKET = "4X25dj1mja51jykqxsXnCH";

    private static final String PRM_JSON = """
            {
                "resource": "http://localhost:9876/mcp",
                "authorization_servers": ["http://localhost:9876"],
                "scopes_supported": ["read"]
            }
            """;

    private static final String AS_METADATA_JSON = """
            {
                "issuer": "http://localhost:9876",
                "authorization_endpoint": "http://localhost:9876/authorize",
                "token_endpoint": "http://localhost:9876/token",
                "registration_endpoint": "http://localhost:9876/register",
                "code_challenge_methods_supported": ["S256"]
            }
            """;

    private static final String REGISTRATION_RESPONSE_JSON = """
            {
                "client_id": "dcr-client-id",
                "client_secret": "dcr-client-secret",
                "redirect_uris": ["http://admin/callback"]
            }
            """;

    private static final String TOKEN_RESPONSE_JSON = """
            {
                "access_token": "access-token",
                "refresh_token": "refresh-token",
                "expires_in": 3600
            }
            """;

    private void setupDiscovery(TestWebServer server) {
        server.map(HttpMethod.POST, "/mcp", 401, "");
        server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp",
                200, PRM_JSON, "Content-Type", "application/json");
        server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server",
                200, AS_METADATA_JSON, "Content-Type", "application/json");
    }

    private void createDcrToolset(String name) {
        Response response = send(HttpMethod.PUT, "/v1/toolsets/" + ADMIN_BUCKET + "/" + name, null, """
                {
                    "endpoint": "http://localhost:9876/mcp",
                    "transport": "HTTP",
                    "allowedTools": [],
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "redirect_uri": "http://admin/callback"
                    }
                }
                """, "authorization", "admin");
        assertEquals(200, response.status(), "DCR toolset creation failed: " + response.body());
    }

    private void signInGlobal(String toolsetName) {
        Response response = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                {
                    "url": "toolsets/%s/%s",
                    "credentialsLevel": "GLOBAL",
                    "authenticationType": "OAUTH",
                    "code": "auth-code"
                }
                """.formatted(ADMIN_BUCKET, toolsetName), "authorization", "admin");
        assertEquals(200, response.status(), "Sign-in failed: " + response.body());
    }

    @Test
    void testRepairForbiddenWhenNoWriteAccess() {
        Response response = send(HttpMethod.POST,
                "/v1/ops/toolset/" + ADMIN_BUCKET + "/some-toolset/repair",
                null, null, "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testRepairAllowedForOwner() throws Exception {
        String userBucket = new io.vertx.core.json.JsonObject(
                send(HttpMethod.GET, "/v1/bucket", null, null, "authorization", "user").body()
        ).getString("bucket");

        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            Response create = send(HttpMethod.PUT,
                    "/v1/toolsets/" + userBucket + "/owner-repair-toolset@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "redirect_uri": "http://localhost/callback"
                        }
                    }
                    """, "authorization", "user");
            assertEquals(200, create.status(), "Toolset creation failed: " + create.body());

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + userBucket + "/owner-repair-toolset@/repair",
                    null, null, "authorization", "user");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("REREGISTERED", result.get("result").asText());
        }
    }

    @Test
    void testRepairNotFoundForMissingToolset() {
        Response response = send(HttpMethod.POST,
                "/v1/ops/toolset/" + ADMIN_BUCKET + "/nonexistent-toolset/repair",
                null, null, "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testRepairRejectedForStaticOauthToolset() {
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");

            Response create = send(HttpMethod.PUT,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-static-repair@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "static-client-id",
                            "client_secret": "static-client-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, create.status());

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + ADMIN_BUCKET + "/toolset-static-repair@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 400);
        }
    }

    @Test
    void testRepairSucceeds() throws Exception {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-reregister@");

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + ADMIN_BUCKET + "/toolset-repair-reregister@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("REREGISTERED", result.get("result").asText());
        }
    }

    @Test
    void testRepairClearsAllLevelsOnReregistration() throws Exception {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-clear-all@");

            signInGlobal("toolset-repair-clear-all@");
            Response userSignin = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/%s/toolset-repair-clear-all@",
                        "credentialsLevel": "USER",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(ADMIN_BUCKET), "authorization", "admin");
            assertEquals(200, userSignin.status(), "USER sign-in failed: " + userSignin.body());

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + ADMIN_BUCKET + "/toolset-repair-clear-all@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());
            assertEquals("REREGISTERED", ProxyUtil.MAPPER.readTree(repair.body()).get("result").asText());

            // Both GLOBAL and USER auth status must be SIGNED_OUT after re-registration
            Response get = send(HttpMethod.GET,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-clear-all@",
                    null, null, "authorization", "admin");
            assertEquals(200, get.status());
            JsonNode authSettings = ProxyUtil.MAPPER.readTree(get.body()).get("auth_settings");
            assertEquals("SIGNED_OUT", authSettings.get("global_auth_status").asText(),
                    "GLOBAL must be SIGNED_OUT after re-registration");
            assertEquals("SIGNED_OUT", authSettings.get("user_level_auth_status").asText(),
                    "USER must be SIGNED_OUT after re-registration");
        }
    }

    @Test
    void testRepairFailsWithFailedDependencyWhenDiscoveryReturnsNotFound() {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-discovery-fail@");

            server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp", 404, "");
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server", 404, "");

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + ADMIN_BUCKET + "/toolset-repair-discovery-fail@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 424);
        }
    }

    @Test
    void testRepairRejectedForNonOauthToolset() {
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 200, "", "Content-Type", "application/json");

            Response create = send(HttpMethod.PUT,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-api-key@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "API_KEY",
                            "api_key_header": "Authorization"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, create.status());

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + ADMIN_BUCKET + "/toolset-api-key@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 400);
        }
    }

    @Test
    void testRepairAllowedForAdminOnPublicToolset() throws Exception {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-pub@");

            Response pubCreate = send(HttpMethod.POST, "/v1/ops/publication/create", null, """
                    {
                      "targetFolder": "public/",
                      "resources": [
                        {
                          "action": "ADD",
                          "sourceUrl": "toolsets/%s/toolset-repair-pub@",
                          "targetUrl": "toolsets/public/toolset-repair-pub@"
                        }
                      ]
                    }
                    """.formatted(ADMIN_BUCKET), "authorization", "admin");
            assertEquals(200, pubCreate.status(), "Publication create failed: " + pubCreate.body());
            String pubUrl = ProxyUtil.MAPPER.readTree(pubCreate.body()).get("url").asText();

            Response pubApprove = send(HttpMethod.POST, "/v1/ops/publication/approve", null, """
                    {"url": "%s"}
                    """.formatted(pubUrl), "authorization", "admin");
            assertEquals(200, pubApprove.status(), "Publication approve failed: " + pubApprove.body());

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/public/toolset-repair-pub@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("REREGISTERED", result.get("result").asText());
        }
    }

    @Test
    void testRepairAllowedForSharedWriteUser() throws Exception {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-shared@");

            Response shareCreate = send(HttpMethod.POST, "/v1/ops/resource/share/create", null, """
                    {
                      "invitationType": "link",
                      "resources": [
                        {
                          "url": "toolsets/%s/toolset-repair-shared@",
                          "permissions": ["WRITE"]
                        }
                      ]
                    }
                    """.formatted(ADMIN_BUCKET), "authorization", "admin");
            assertEquals(200, shareCreate.status(), "Share create failed: " + shareCreate.body());
            InvitationLink invitationLink = ProxyUtil.convertToObject(shareCreate.body(), InvitationLink.class);
            assertNotNull(invitationLink);

            Response accept = send(HttpMethod.GET,
                    invitationLink.invitationLink(), "accept=true", null, "authorization", "user");
            assertEquals(200, accept.status(), "Invitation accept failed: " + accept.body());

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + ADMIN_BUCKET + "/toolset-repair-shared@/repair",
                    null, null, "authorization", "user");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("REREGISTERED", result.get("result").asText());
        }
    }

    @Test
    void testRepairForbiddenForAdminOnPrivateToolset() {
        String userBucket = new io.vertx.core.json.JsonObject(
                send(HttpMethod.GET, "/v1/bucket", null, null, "authorization", "user").body()
        ).getString("bucket");

        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            Response create = send(HttpMethod.PUT,
                    "/v1/toolsets/" + userBucket + "/toolset-repair-priv@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "redirect_uri": "http://localhost/callback"
                        }
                    }
                    """, "authorization", "user");
            assertEquals(200, create.status(), "Toolset creation failed: " + create.body());

            Response repair = send(HttpMethod.POST,
                    "/v1/ops/toolset/" + userBucket + "/toolset-repair-priv@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 403);
        }
    }
}
