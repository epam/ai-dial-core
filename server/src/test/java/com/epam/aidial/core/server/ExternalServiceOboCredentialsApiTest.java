package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * On-behalf-of (OBO) credential retrieval — a trusted actor (e.g. the Scheduler) authenticated by its own
 * DIAL project key retrieves an absent owner's external-service credential. Exercises the actor gate,
 * offline-usage consent, fail-closed (no fallback) resolution, refresh, and the PRK/project-key invariants.
 */
public class ExternalServiceOboCredentialsApiTest extends ResourceBaseTest {

    private static final String SALESFORCE_SCOPE = "applications/app-with-services/external_services/salesforce";
    private static final String BILLING_SCOPE = "applications/app-with-services/external_services/billing-api";
    private static final String OTHER_BILLING_SCOPE = "applications/other-app/external_services/billing-api";
    private static final String WORKLOAD_BILLING_SCOPE = "applications/workload-app/external_services/billing-api";
    private static final String WORKLOAD_CLIENT_ID = "scheduler-client-id";   // workload-app's app_identity (azp form)
    private static final String USER_SVC_SCOPE = "applications/user-services-app/external_services/myapi";
    private static final String USER_SVC_PUT = "/v1/applications/user-services-app/external-services/myapi";
    private static final String USER_SVC_BODY = """
            {
                "display_name": "My API",
                "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-My-Key" }
            }
            """;

    private static final String SCHEDULER_KEY = "scheduler-key";   // trusted (its SHA-256 is the app_identity)
    private static final String UNTRUSTED_KEY = "untrusted-key";   // not trusted
    private static final String SCHEDULER_KEY_HASH = "935cd3c27ffd8bd295f5933665f298aad638a289c0cfe3a9a3e8685dade379c7";

    private static final String OAUTH_TOKEN_RESPONSE_REFRESHED = """
            {
                "access_token": "access-token-2",
                "refresh_token": "refresh-token-2",
                "expires_in": 3600
            }
            """;

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboHappyPathApiKey() throws Exception {
        verify(signInUserBilling("user-billing-key", true), 200, "true");

        Response obo = obo(BILLING_SCOPE, "user", SCHEDULER_KEY);
        assertEquals(200, obo.status(), () -> obo.body());
        JsonNode body = ProxyUtil.MAPPER.readTree(obo.body());
        assertEquals("X-API-Key", body.get("header_name").asText());
        assertEquals("user-billing-key", body.get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboOauthRefresh() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        // Sign-in code-exchange (call 0) returns expires_in=0 to force a refresh at OBO retrieval time.
        TestWebServer.Handler handler = request -> {
            String body = counter.getAndIncrement() == 0
                    ? "{\"access_token\":\"access-token-1\",\"refresh_token\":\"refresh-token-1\",\"expires_in\":0}"
                    : OAUTH_TOKEN_RESPONSE_REFRESHED;
            return new MockResponse().setBody(body).setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            verify(signInUserOauth(true), 200, "true");

            Response obo = obo(SALESFORCE_SCOPE, "user", SCHEDULER_KEY);
            assertEquals(200, obo.status(), () -> obo.body());
            JsonNode body = ProxyUtil.MAPPER.readTree(obo.body());
            assertEquals("Bearer access-token-2", body.get("header_value").asText());
            assertNotNull(body.get("expires_at"));
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboActorGateDeniesUntrustedProject() throws Exception {
        verify(signInUserBilling("user-billing-key", true), 200, "true");

        Response obo = obo(BILLING_SCOPE, "user", UNTRUSTED_KEY);
        assertEquals(403, obo.status(), () -> obo.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboRequiresOfflineUsageConsent() throws Exception {
        // Signed in WITHOUT offline-usage consent — OBO must be refused (403), distinct from a 404.
        verify(signInUserBilling("user-billing-key", false), 200, "true");

        Response obo = obo(BILLING_SCOPE, "user", SCHEDULER_KEY);
        assertEquals(403, obo.status(), () -> obo.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboFailsClosedNoFallbackToApplicationLevel() throws Exception {
        // Only an APPLICATION-level credential exists; the owner has no USER-level credential.
        // OBO is USER-level only, so it must NOT fall back to the app-level token → 404, secret not leaked.
        Response appSign = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared-app-key"
                }
                """.formatted(BILLING_SCOPE), "authorization", "admin");
        verify(appSign, 200, "true");

        Response obo = obo(BILLING_SCOPE, "user", SCHEDULER_KEY);
        assertEquals(404, obo.status(), () -> obo.body());
        assertFalse(obo.body().contains("shared-app-key"), "must not fall back to / leak the app-level credential");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboRevokedOnSignOut() throws Exception {
        verify(signInUserBilling("user-billing-key", true), 200, "true");
        assertEquals(200, obo(BILLING_SCOPE, "user", SCHEDULER_KEY).status());

        Response signOut = send(HttpMethod.POST, "/v1/ops/external-service/signout", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY"
                }
                """.formatted(BILLING_SCOPE), "authorization", "user");
        verify(signOut, 200, "true");

        // Sign-out dropped the credential (and its consent) → further OBO issuance stops.
        assertEquals(404, obo(BILLING_SCOPE, "user", SCHEDULER_KEY).status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboPermanentRefreshFailureReturns401() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        // Sign-in succeeds with expires_in=0; the refresh at OBO time is rejected (4xx invalid_grant).
        TestWebServer.Handler handler = request -> {
            if (counter.getAndIncrement() == 0) {
                return new MockResponse()
                        .setBody("{\"access_token\":\"access-token-1\",\"refresh_token\":\"refresh-token-1\",\"expires_in\":0}")
                        .setHeader("Content-Type", "application/json");
            }
            return new MockResponse().setResponseCode(400).setBody("{\"error\":\"invalid_grant\"}")
                    .setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            verify(signInUserOauth(true), 200, "true");

            Response obo = obo(SALESFORCE_SCOPE, "user", SCHEDULER_KEY);
            assertEquals(401, obo.status(), () -> obo.body());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboRejectsPerRequestKey() {
        ApiKeyData prk = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(prk);
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\",\"owner_sub\":\"user\"}",
                "api-key", prk.getPerRequestKey());
        assertEquals(401, resp.status(), () -> resp.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testProjectKeyRejectedOnPerRequestCredentialsEndpoint() {
        // Mirror invariant: the PRK-only /credentials endpoint rejects a plain project key.
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}", "api-key", SCHEDULER_KEY);
        assertEquals(401, resp.status(), () -> resp.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboActorGateKeyedToScopeApp() throws Exception {
        // The scheduler is trusted by app-with-services, but other-app declares NO app_identity.
        // The gate is keyed to the scope's owning app, so the same key cannot reach other-app's services.
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "k",
                    "offline_usage_consent": true
                }
                """.formatted(OTHER_BILLING_SCOPE), "authorization", "user"), 200, "true");

        Response obo = obo(OTHER_BILLING_SCOPE, "user", SCHEDULER_KEY);
        assertEquals(403, obo.status(), () -> obo.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboMissingOwnerSubRejected() {
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}", "api-key", SCHEDULER_KEY);
        assertEquals(400, resp.status(), () -> resp.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboDeniesJwtCallerWithoutMatchingIdentity() throws Exception {
        verify(signInUserBilling("user-billing-key", true), 200, "true");
        // A plain JWT user (no azp, no matching key) does not match the app's app_identity ⇒ 403.
        Response obo = send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\",\"owner_sub\":\"user\"}", "authorization", "user");
        assertEquals(403, obo.status(), () -> obo.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboWorkloadIdentityMatch() throws Exception {
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "user-workload-key",
                    "offline_usage_consent": true
                }
                """.formatted(WORKLOAD_BILLING_SCOPE), "authorization", "user"), 200, "true");

        // Workload caller authenticates with a JWT whose azp equals workload-app's app_identity.
        Response obo = oboWithAuth(WORKLOAD_BILLING_SCOPE, "user", "azp:" + WORKLOAD_CLIENT_ID);
        assertEquals(200, obo.status(), () -> obo.body());
        assertEquals("user-workload-key", ProxyUtil.MAPPER.readTree(obo.body()).get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboWorkloadIdentityMismatch() throws Exception {
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "user-workload-key",
                    "offline_usage_consent": true
                }
                """.formatted(WORKLOAD_BILLING_SCOPE), "authorization", "user"), 200, "true");

        Response obo = oboWithAuth(WORKLOAD_BILLING_SCOPE, "user", "azp:wrong-client-id");
        assertEquals(403, obo.status(), () -> obo.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testDynamicAppPutStripsAdminManagedFields() throws Exception {
        String bucket = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, "/v1/bucket", null, "").body())
                .get("bucket").asText();
        String appUrl = "applications/" + bucket + "/gov-dynamic-app";

        Response create = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Gov Dynamic App",
                    "app_identity": "%s",
                    "allow_user_external_services": true
                }
                """.formatted(SCHEDULER_KEY_HASH), "api-key", "proxyKey1");
        assertEquals(200, create.status(), () -> create.body());

        Response get = send(HttpMethod.GET, "/v1/" + appUrl, null, "", "api-key", "proxyKey1");
        assertFalse(get.body().contains("app_identity"), () -> "dynamic PUT must not set app_identity: " + get.body());
        assertFalse(get.body().contains("allow_user_external_services"),
                () -> "dynamic PUT must not set allow_user_external_services: " + get.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testAdminApplySetsAdminManagedFields() throws Exception {
        String apply = """
                {
                  "manifests": [
                    {
                      "kind": "Application",
                      "name": "gov-applied-app",
                      "spec": {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Gov Applied App",
                        "app_identity": "%s",
                        "allow_user_external_services": true
                      }
                    }
                  ]
                }
                """.formatted(SCHEDULER_KEY_HASH);
        Response applyResp = send(HttpMethod.POST, "/v1/admin/apply", null, apply, "authorization", "admin");
        assertEquals(200, applyResp.status(), () -> applyResp.body());

        String appUrl = "/v1/applications/public/gov-applied-app";
        JsonNode adminApp = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, appUrl, null, "", "authorization", "admin").body());
        assertEquals(SCHEDULER_KEY_HASH, adminApp.get("app_identity").asText());
        assertTrue(adminApp.get("allow_user_external_services").asBoolean());

        Response userGet = send(HttpMethod.GET, appUrl, null, "", "api-key", "proxyKey1");
        assertEquals(200, userGet.status(), () -> userGet.body());
        assertFalse(userGet.body().contains(SCHEDULER_KEY_HASH),
                () -> "non-admin must not see app_identity value: " + userGet.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceEndToEnd() throws Exception {
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());

        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "my-secret-key",
                    "offline_usage_consent": true
                }
                """.formatted(USER_SVC_SCOPE), "authorization", "user"), 200, "true");

        ApiKeyData appKey = newAppKey("user-services-app", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response cred = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + USER_SVC_SCOPE + "\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(200, cred.status(), () -> cred.body());
        JsonNode body = ProxyUtil.MAPPER.readTree(cred.body());
        assertEquals("X-My-Key", body.get("header_name").asText());
        assertEquals("my-secret-key", body.get("header_value").asText());

        Response obo = obo(USER_SVC_SCOPE, "user", SCHEDULER_KEY);
        assertEquals(200, obo.status(), () -> obo.body());
        assertEquals("my-secret-key", ProxyUtil.MAPPER.readTree(obo.body()).get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceIsolatedPerUser() {
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());

        // A different identity (admin) has no such service in its own bucket → resolves to 404.
        Response otherSignIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "x"
                }
                """.formatted(USER_SVC_SCOPE), "authorization", "admin");
        assertEquals(404, otherSignIn.status(), () -> otherSignIn.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoringDeniedWhenFlagOff() {
        // app-with-services does not set allow_user_external_services.
        Response put = send(HttpMethod.PUT, "/v1/applications/app-with-services/external-services/myapi",
                null, USER_SVC_BODY, "authorization", "user");
        assertEquals(403, put.status(), () -> put.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceDelete() {
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());
        assertEquals(200, send(HttpMethod.DELETE, USER_SVC_PUT, null, "", "authorization", "user").status());

        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "x"
                }
                """.formatted(USER_SVC_SCOPE), "authorization", "user");
        assertEquals(404, signIn.status(), () -> signIn.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceReadableViaManagementGet() throws Exception {
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());

        // Before Phase 2 the author got 403 here; now they can read back their own definition.
        Response get = send(HttpMethod.GET, USER_SVC_PUT, null, "", "authorization", "user");
        assertEquals(200, get.status(), () -> get.body());
        JsonNode body = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("myapi", body.get("id").asText());
        assertEquals("My API", body.get("display_name").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceListedViaManagement() throws Exception {
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());

        Response list = send(HttpMethod.GET, "/v1/applications/user-services-app/external-services",
                null, "", "authorization", "user");
        assertEquals(200, list.status(), () -> list.body());
        JsonNode arr = ProxyUtil.MAPPER.readTree(list.body());
        assertTrue(arr.isArray(), () -> list.body());
        assertEquals(1, arr.size(), () -> list.body());
        assertEquals("myapi", arr.get(0).get("id").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testManagementReadIsolatedFromInlineAdmin() throws Exception {
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());

        // Admin manages the app's inline definitions (none here); the author's private overlay is invisible.
        Response adminList = send(HttpMethod.GET, "/v1/applications/user-services-app/external-services",
                null, "", "authorization", "admin");
        assertEquals(200, adminList.status(), () -> adminList.body());
        assertEquals(0, ProxyUtil.MAPPER.readTree(adminList.body()).size(), () -> adminList.body());

        Response adminGet = send(HttpMethod.GET, USER_SVC_PUT, null, "", "authorization", "admin");
        assertEquals(404, adminGet.status(), () -> adminGet.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testManagementGetDeniedWhenFlagOff() {
        // app-with-services does not enable allow_user_external_services → non-privileged read is refused.
        Response get = send(HttpMethod.GET, "/v1/applications/app-with-services/external-services/salesforce",
                null, "", "authorization", "user");
        assertEquals(403, get.status(), () -> get.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceInDeploymentView() throws Exception {
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());

        Response deployment = send(HttpMethod.GET, "/openai/applications/user-services-app", null, "", "authorization", "user");
        assertEquals(200, deployment.status(), () -> deployment.body());
        JsonNode services = ProxyUtil.MAPPER.readTree(deployment.body()).get("external_services");
        assertNotNull(services, () -> deployment.body());
        assertNotNull(services.get("myapi"), () -> deployment.body());
        assertEquals("My API", services.get("myapi").get("display_name").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceInRawResourceGet() throws Exception {
        // Admin publishes a public app that opts into user-authored services.
        Response apply = send(HttpMethod.POST, "/v1/admin/apply", null, """
                {
                  "manifests": [
                    {
                      "kind": "Application",
                      "name": "raw-user-svc-app",
                      "spec": {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Raw User Svc App",
                        "allow_user_external_services": true
                      }
                    }
                  ]
                }
                """, "authorization", "admin");
        assertEquals(200, apply.status(), () -> apply.body());

        String appId = "public/raw-user-svc-app";
        assertEquals(200, send(HttpMethod.PUT, "/v1/applications/" + appId + "/external-services/myapi",
                null, USER_SVC_BODY, "authorization", "user").status());

        Response raw = send(HttpMethod.GET, "/v1/applications/" + appId, null, "", "authorization", "user");
        assertEquals(200, raw.status(), () -> raw.body());
        JsonNode services = ProxyUtil.MAPPER.readTree(raw.body()).get("external_services");
        assertNotNull(services, () -> raw.body());
        assertNotNull(services.get("myapi"), () -> raw.body());
        assertEquals("My API", services.get("myapi").get("display_name").asText());
    }

    private Response obo(String url, String ownerSub, String apiKeyValue) {
        return send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                "{\"url\":\"" + url + "\",\"owner_sub\":\"" + ownerSub + "\"}",
                "api-key", apiKeyValue);
    }

    private Response oboWithAuth(String url, String ownerSub, String authValue) {
        return send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                "{\"url\":\"" + url + "\",\"owner_sub\":\"" + ownerSub + "\"}",
                "authorization", authValue);
    }

    private Response signInUserBilling(String apiKey, boolean offlineConsent) {
        return send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "%s",
                    "offline_usage_consent": %s
                }
                """.formatted(BILLING_SCOPE, apiKey, offlineConsent), "authorization", "user");
    }

    private Response signInUserOauth(boolean offlineConsent) {
        return send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "OAUTH",
                    "code": "auth-code",
                    "offline_usage_consent": %s
                }
                """.formatted(SALESFORCE_SCOPE, offlineConsent), "authorization", "user");
    }

    private ApiKeyData newAppKey(String sourceDeployment, String role) {
        ApiKeyData perRequestKey = new ApiKeyData();
        perRequestKey.setExtractedClaims(createClaims(role));
        perRequestKey.setSourceDeployment(sourceDeployment);
        perRequestKey.setTraceId("trace-id");
        return perRequestKey;
    }
}
