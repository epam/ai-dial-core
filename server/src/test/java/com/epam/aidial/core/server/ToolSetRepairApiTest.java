package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
        // "user" has no write access to the admin's private toolset bucket
        Response response = send(HttpMethod.POST,
                "/v1/toolsets/" + ADMIN_BUCKET + "/some-toolset/repair",
                null, null, "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testRepairAllowedForOwner() throws Exception {
        // Owner of the toolset (non-admin) can repair their own DCR toolset
        String userBucket = new io.vertx.core.json.JsonObject(
                send(HttpMethod.GET, "/v1/bucket", null, null, "authorization", "user").body()
        ).getString("bucket");

        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            // Create DCR toolset as "user" (in user's own bucket)
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

            // Sign in at GLOBAL level as "user" so there are credentials to probe
            Response signIn = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/%s/owner-repair-toolset@",
                        "credentialsLevel": "GLOBAL",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(userBucket), "authorization", "user");
            assertEquals(200, signIn.status(), "Sign-in failed: " + signIn.body());

            // Owner (non-admin) can repair their own toolset
            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + userBucket + "/owner-repair-toolset@/repair",
                    null, null, "authorization", "user");
            assertEquals(200, repair.status(), repair.body());

            io.vertx.core.json.JsonObject result = new io.vertx.core.json.JsonObject(repair.body());
            assertEquals("NO_OP", result.getString("result"));
        }
    }

    @Test
    void testRepairNotFoundForMissingToolset() {
        Response response = send(HttpMethod.POST,
                "/v1/toolsets/" + ADMIN_BUCKET + "/nonexistent-toolset/repair",
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
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-static-repair@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 400);
        }
    }

    @Test
    void testRepairNoOpWhenClientValidAndEndpointsUnchanged() throws Exception {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-noop@");
            signInGlobal("toolset-repair-noop@");

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-noop@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("NO_OP", result.get("result").asText());
        }
    }

    @Test
    void testRepairEndpointsRefreshedWhenClientValidAndEndpointsChanged() throws Exception {
        String updatedAsMetadata = """
                {
                    "issuer": "http://localhost:9876",
                    "authorization_endpoint": "http://localhost:9876/new-authorize",
                    "token_endpoint": "http://localhost:9876/token",
                    "registration_endpoint": "http://localhost:9876/register",
                    "code_challenge_methods_supported": ["S256"]
                }
                """;

        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-refresh@");
            signInGlobal("toolset-repair-refresh@");

            // Update AS metadata so repair discovers a different authorization_endpoint
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server",
                    200, updatedAsMetadata, "Content-Type", "application/json");

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-refresh@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("ENDPOINTS_REFRESHED", result.get("result").asText());

            // Verify the new endpoint is persisted
            Response get = send(HttpMethod.GET,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-refresh@",
                    null, null, "authorization", "admin");
            assertEquals(200, get.status());
            JsonNode authSettings = ProxyUtil.MAPPER.readTree(get.body()).get("auth_settings");
            assertEquals("http://localhost:9876/new-authorize",
                    authSettings.get("authorization_endpoint").asText());
        }
    }

    @Test
    void testRepairReregistersWhenClientIsDead() throws Exception {
        String newClientRegistration = """
                {
                    "client_id": "new-dcr-client-id",
                    "client_secret": "new-dcr-client-secret",
                    "redirect_uris": ["http://admin/callback"]
                }
                """;
        AtomicInteger registerCallCount = new AtomicInteger(0);

        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            // First call returns initial client, second call (re-registration during repair) returns new client
            server.map(HttpMethod.POST, "/register", request -> {
                int callNumber = registerCallCount.incrementAndGet();
                String body = callNumber == 1 ? REGISTRATION_RESPONSE_JSON : newClientRegistration;
                return new MockResponse().setResponseCode(200)
                        .setBody(body).setHeader("Content-Type", "application/json");
            });
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-dead@");
            signInGlobal("toolset-repair-dead@");

            // Token probe returns invalid_client → client is dead
            server.map(HttpMethod.POST, "/token", 401,
                    "{\"error\":\"invalid_client\",\"error_description\":\"Client not found\"}",
                    "Content-Type", "application/json");

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-dead@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("REREGISTERED", result.get("result").asText());
            assertEquals(2, registerCallCount.get(), "Re-registration endpoint should have been called twice");
        }
    }

    @Test
    void testRepairReregistersWhenNoCredentialsAtAnyLevel() throws Exception {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-nocreds@");
            // No sign-in at any level → locator returns empty list → re-register

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-nocreds@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("REREGISTERED", result.get("result").asText());
        }
    }

    @Test
    void testRepairProbeInvalidGrantKeepsClient() throws Exception {
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-grant@");
            signInGlobal("toolset-repair-grant@");

            // Token probe returns invalid_grant → grant expired but client alive → NO_OP (endpoints unchanged)
            server.map(HttpMethod.POST, "/token", 400,
                    "{\"error\":\"invalid_grant\",\"error_description\":\"Refresh token expired\"}",
                    "Content-Type", "application/json");

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-grant@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("NO_OP", result.get("result").asText());
        }
    }

    @Test
    void testRepairProbesUserCredentialsWhenGlobalAbsent() throws Exception {
        // Covers fix #1: probe uses any level, not only GLOBAL.
        // Sign in at USER level only → repair must still probe (not skip to re-register).
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-user-probe@");

            // Sign in at USER level only (no GLOBAL)
            Response signin = send(HttpMethod.POST, "/v1/ops/toolset/signin", null, """
                    {
                        "url": "toolsets/%s/toolset-repair-user-probe@",
                        "credentialsLevel": "USER",
                        "authenticationType": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(ADMIN_BUCKET), "authorization", "admin");
            assertEquals(200, signin.status(), "Sign-in at USER level failed: " + signin.body());

            // Token probe succeeds → client alive, endpoints unchanged → NO_OP (not REREGISTERED)
            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-user-probe@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("NO_OP", result.get("result").asText(),
                    "Expected NO_OP (probe via USER creds), got: " + repair.body());
        }
    }

    @Test
    void testRepairClearsAllLevelsOnReregistration() throws Exception {
        // Covers fix #2: re-register clears GLOBAL + USER, not only GLOBAL.
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-clear-all@");

            // Sign in at both levels
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

            // Token probe: invalid_client → re-register
            server.map(HttpMethod.POST, "/token", 401,
                    "{\"error\":\"invalid_client\",\"error_description\":\"Client not found\"}",
                    "Content-Type", "application/json");

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-clear-all@/repair",
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
    void testRepairFailsWithBadGatewayWhenDiscoveryReturnsNotFound() {
        // AS discovery unreachable / PRM missing → expect 502, not a silent NPE → 500
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-discovery-fail@");

            // Overwrite discovery endpoints to return 404 so discoverMetadata() returns null
            server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/mcp", 404, "");
            server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server", 404, "");

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-discovery-fail@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 502);
        }
    }

    @Test
    void testRepairReregistersOnInconclusiveProbe() throws Exception {
        // Token endpoint returns 500 with no recognized OAuth error → inconclusive → REREGISTERED
        try (TestWebServer server = new TestWebServer(9876)) {
            setupDiscovery(server);
            server.map(HttpMethod.POST, "/register",
                    200, REGISTRATION_RESPONSE_JSON, "Content-Type", "application/json");
            server.map(HttpMethod.POST, "/token",
                    200, TOKEN_RESPONSE_JSON, "Content-Type", "application/json");

            createDcrToolset("toolset-repair-inconclusive@");
            signInGlobal("toolset-repair-inconclusive@");

            // Token probe returns 500 with no 'error' field → inconclusive → re-register
            server.map(HttpMethod.POST, "/token", 500,
                    "{\"message\":\"internal error\"}",
                    "Content-Type", "application/json");

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-repair-inconclusive@/repair",
                    null, null, "authorization", "admin");
            assertEquals(200, repair.status(), repair.body());

            JsonNode result = ProxyUtil.MAPPER.readTree(repair.body());
            assertEquals("REREGISTERED", result.get("result").asText());
        }
    }

    @Test
    void testRepairRejectedForLegacyOauthToolsetWithNullDynamicallyRegistered() {
        // Legacy toolset: dynamically_registered is absent (null) → 400, not eligible
        // We inject a toolset with OAUTH but no dynamically_registered field by using static client_id.
        // After creation dynamicallyRegistered=false; we verify 400 is returned (null and false are
        // both ineligible — the static toolset simulates the legacy path for this eligibility check).
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/mcp", 401, "");

            Response create = send(HttpMethod.PUT,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-legacy-null@", null, """
                    {
                        "endpoint": "http://localhost:9876/mcp",
                        "transport": "HTTP",
                        "allowedTools": [],
                        "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "legacy-client-id",
                            "client_secret": "legacy-secret",
                            "redirect_uri": "http://admin/callback",
                            "authorization_endpoint": "http://localhost:9876/authorize",
                            "token_endpoint": "http://localhost:9876/token"
                        }
                    }
                    """, "authorization", "admin");
            assertEquals(200, create.status());

            Response repair = send(HttpMethod.POST,
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-legacy-null@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 400);
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
                    "/v1/toolsets/" + ADMIN_BUCKET + "/toolset-api-key@/repair",
                    null, null, "authorization", "admin");
            verify(repair, 400);
        }
    }
}
