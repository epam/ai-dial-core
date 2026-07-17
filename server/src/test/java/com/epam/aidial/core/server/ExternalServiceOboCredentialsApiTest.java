package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void testOboUntrustedCallerCannotDistinguishMissingFromForbidden() throws Exception {
        // The actor gate runs before any owner-scoped resolution, so an untrusted caller gets a uniform 403
        // whether the service exists or not — no 404-vs-403 enumeration oracle over who authored what.
        int existing = obo(BILLING_SCOPE, "user", UNTRUSTED_KEY).status();
        int missing = obo("applications/app-with-services/external_services/no-such-service", "user", UNTRUSTED_KEY).status();
        assertEquals(403, existing);
        assertEquals(403, missing, "untrusted caller must not learn a service is absent (would be 404 if gated after resolution)");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserlessCallerCannotAuthorUserExternalService() throws Exception {
        // A project key has no user sub (getUserId()==null). It must not fall into the user-authoring branch and
        // derive the shared "Users/null/" bucket (a namespace shared across all userless callers) — reject 403.
        Response put = send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "api-key", "proxyKey1");
        assertEquals(403, put.status(), () -> put.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboTrustedKeyOnAppWithDifferentIdentityDenied() throws Exception {
        // scheduler-key matches app-with-services, but workload-app declares a different, non-null app_identity
        // (exercises the non-matching branch, distinct from other-app's null app_identity).
        assertEquals(403, obo(WORKLOAD_BILLING_SCOPE, "user", SCHEDULER_KEY).status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboTrustedCallerMissingAppOrServiceReturns404() throws Exception {
        // Gate passes (trusted); a valid-shape url for a non-existent app or service is a plain 404.
        assertEquals(404, obo("applications/no-such-app/external_services/x", "user", SCHEDULER_KEY).status());
        assertEquals(404, obo("applications/app-with-services/external_services/no-such", "user", SCHEDULER_KEY).status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboMissingOwnerCredentialReturns404() throws Exception {
        // Real, trusted service but the named owner never signed in ⇒ no USER credential ⇒ fail closed with 404.
        assertEquals(404, obo(BILLING_SCOPE, "ghost-user", SCHEDULER_KEY).status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboAppidClaimNotHonored() throws Exception {
        // Azure v1 appid is not accepted as the actor identity (only azp is) ⇒ no match ⇒ 403.
        assertEquals(403, oboWithAuth(WORKLOAD_BILLING_SCOPE, "user", "appid:" + WORKLOAD_CLIENT_ID).status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboDeniedResponsesDoNotLeakCredential() throws Exception {
        verify(signInUserBilling("super-secret-key", false), 200, "true");

        Response noConsent = obo(BILLING_SCOPE, "user", SCHEDULER_KEY);
        assertEquals(403, noConsent.status(), () -> noConsent.body());
        assertFalse(noConsent.body().contains("super-secret-key"), () -> noConsent.body());

        Response gateMismatch = obo(BILLING_SCOPE, "user", UNTRUSTED_KEY);
        assertEquals(403, gateMismatch.status(), () -> gateMismatch.body());
        assertFalse(gateMismatch.body().contains("super-secret-key"), () -> gateMismatch.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboOauthExpiresAtIsEpochSeconds() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
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
            long expiresAt = ProxyUtil.MAPPER.readTree(obo.body()).get("expires_at").asLong();
            // Epoch seconds (~1.7e9), not milliseconds (~1.7e12) — guards the ms→s conversion.
            assertTrue(expiresAt > 1_000_000_000L && expiresAt < 10_000_000_000L,
                    () -> "expires_at should be epoch seconds: " + expiresAt);
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testInlineServiceWinsOverUserAuthoredIdClash() throws Exception {
        assertEquals(200, send(HttpMethod.PUT, "/v1/applications/collision-app/external-services/billing-api", null, """
                {
                    "display_name": "User Billing",
                    "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-User-Key" }
                }
                """, "authorization", "user").status());

        Response view = send(HttpMethod.GET, "/openai/applications/collision-app", null, "", "authorization", "user");
        assertEquals(200, view.status(), () -> view.body());
        JsonNode billing = ProxyUtil.MAPPER.readTree(view.body()).get("external_services").get("billing-api");
        assertEquals("Inline Billing", billing.get("display_name").asText(), () -> view.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredOauthServiceSecretStrippedAndResolvable() throws Exception {
        String put = "/v1/applications/user-services-app/external-services/myoauth";
        String scope = "applications/user-services-app/external_services/myoauth";
        assertEquals(200, send(HttpMethod.PUT, put, null, """
                {
                    "display_name": "My OAuth",
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid",
                        "client_secret": "shh-secret",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876/token",
                        "redirect_uri": "http://localhost:3000/cb"
                    }
                }
                """, "authorization", "user").status());

        Response get = send(HttpMethod.GET, put, null, "", "authorization", "user");
        assertEquals(200, get.status(), () -> get.body());
        assertFalse(get.body().contains("shh-secret"), () -> "client_secret must be stripped on read: " + get.body());

        AtomicInteger counter = new AtomicInteger(0);
        TestWebServer.Handler handler = request -> {
            String body = counter.getAndIncrement() == 0
                    ? "{\"access_token\":\"at-1\",\"refresh_token\":\"rt-1\",\"expires_in\":3600}"
                    : OAUTH_TOKEN_RESPONSE_REFRESHED;
            return new MockResponse().setBody(body).setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code",
                        "offline_usage_consent": true
                    }
                    """.formatted(scope), "authorization", "user"), 200, "true");

            Response obo = obo(scope, "user", SCHEDULER_KEY);
            assertEquals(200, obo.status(), () -> obo.body());
            assertEquals("Bearer at-1", ProxyUtil.MAPPER.readTree(obo.body()).get("header_value").asText());
        }
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
        Response adminGet = send(HttpMethod.GET, appUrl, null, "", "authorization", "admin");
        JsonNode adminApp = ProxyUtil.MAPPER.readTree(adminGet.body());
        // allow_user_external_services is readable governance; app_identity is admin-readable only (it is
        // verification material, not a credential) so the Admin UI can display and round-trip grants.
        assertTrue(adminApp.get("allow_user_external_services").asBoolean(), adminGet::body);
        assertEquals(SCHEDULER_KEY_HASH, adminApp.path("app_identity").asText(),
                () -> "admin read must expose app_identity: " + adminGet.body());

        Response userGet = send(HttpMethod.GET, appUrl, null, "", "api-key", "proxyKey1");
        assertEquals(200, userGet.status(), () -> userGet.body());
        assertFalse(userGet.body().contains(SCHEDULER_KEY_HASH),
                () -> "non-admin must not see app_identity value: " + userGet.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testAdminManagedFieldsSurviveResourceEdit() throws Exception {
        // Admin-apply an app that enables OBO (app_identity) and user authoring.
        Response apply = send(HttpMethod.POST, "/v1/admin/apply", null, """
                {
                  "manifests": [
                    {
                      "kind": "Application",
                      "name": "gov-edit-app",
                      "spec": {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Gov Edit App",
                        "app_identity": "%s",
                        "allow_user_external_services": true
                      }
                    }
                  ]
                }
                """.formatted(SCHEDULER_KEY_HASH), "authorization", "admin");
        assertEquals(200, apply.status(), () -> apply.body());

        String appUrl = "/v1/applications/public/gov-edit-app";
        // A benign edit that omits the admin-managed fields must NOT wipe them (absent = inherit).
        Response edit = send(HttpMethod.PUT, appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Gov Edit App RENAMED"
                }
                """, "authorization", "admin");
        assertEquals(200, edit.status(), () -> edit.body());

        JsonNode afterEdit = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, appUrl, null, "", "authorization", "admin").body());
        assertTrue(afterEdit.get("allow_user_external_services").asBoolean(), afterEdit::toString);

        // Functionally prove app_identity also survived: author a user service, sign in, retrieve via OBO.
        // (allow_user_external_services must have survived too, else authoring/resolution would 403/404.)
        String scope = "applications/public/gov-edit-app/external_services/myapi";
        assertEquals(200, send(HttpMethod.PUT, appUrl + "/external-services/myapi", null, USER_SVC_BODY, "authorization", "user").status());
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "edit-secret",
                    "offline_usage_consent": true
                }
                """.formatted(scope), "authorization", "user"), 200, "true");

        Response obo = obo(scope, "user", SCHEDULER_KEY);
        assertEquals(200, obo.status(), () -> "app_identity must survive the edit: " + obo.body());
        assertEquals("edit-secret", ProxyUtil.MAPPER.readTree(obo.body()).get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testAdminPublicPutSetsAdminManagedFields() throws Exception {
        // An admin PUT to the public bucket (the Admin UI write path) is authoritative for the admin-managed
        // fields when they are present in the body — same trust posture as config file / admin-apply.
        String appUrl = "/v1/applications/public/gov-admin-public-app";
        Response create = send(HttpMethod.PUT, appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Gov Admin Public App",
                    "app_identity": "%s",
                    "allow_user_external_services": true
                }
                """.formatted(SCHEDULER_KEY_HASH), "authorization", "admin");
        assertEquals(200, create.status(), () -> create.body());

        JsonNode adminApp = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, appUrl, null, "", "authorization", "admin").body());
        assertEquals(SCHEDULER_KEY_HASH, adminApp.path("app_identity").asText(), adminApp::toString);
        assertTrue(adminApp.get("allow_user_external_services").asBoolean(), adminApp::toString);

        Response userGet = send(HttpMethod.GET, appUrl, null, "", "api-key", "proxyKey1");
        assertEquals(200, userGet.status(), () -> userGet.body());
        assertFalse(userGet.body().contains(SCHEDULER_KEY_HASH),
                () -> "non-admin must not see app_identity value: " + userGet.body());

        // Functionally prove the PUT-granted identity: author a user service, sign in, retrieve via OBO.
        String scope = "applications/public/gov-admin-public-app/external_services/myapi";
        assertEquals(200, send(HttpMethod.PUT, appUrl + "/external-services/myapi", null, USER_SVC_BODY, "authorization", "user").status());
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "put-grant-secret",
                    "offline_usage_consent": true
                }
                """.formatted(scope), "authorization", "user"), 200, "true");
        Response obo = obo(scope, "user", SCHEDULER_KEY);
        assertEquals(200, obo.status(), () -> "PUT-granted app_identity must pass the OBO gate: " + obo.body());
        assertEquals("put-grant-secret", ProxyUtil.MAPPER.readTree(obo.body()).get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testAdminPublicPutExplicitValuesClearGrant() throws Exception {
        // Present-as-null / present-as-false are explicit admin revocations; the grant must not survive.
        String appUrl = "/v1/applications/public/gov-clear-app";
        assertEquals(200, send(HttpMethod.PUT, appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Gov Clear App",
                    "app_identity": "%s",
                    "allow_user_external_services": true
                }
                """.formatted(SCHEDULER_KEY_HASH), "authorization", "admin").status());

        Response revoke = send(HttpMethod.PUT, appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Gov Clear App",
                    "app_identity": null,
                    "allow_user_external_services": false
                }
                """, "authorization", "admin");
        assertEquals(200, revoke.status(), () -> revoke.body());

        JsonNode afterRevoke = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, appUrl, null, "", "authorization", "admin").body());
        assertTrue(afterRevoke.path("app_identity").isMissingNode() || afterRevoke.path("app_identity").isNull(),
                afterRevoke::toString);
        assertFalse(afterRevoke.path("allow_user_external_services").asBoolean(false), afterRevoke::toString);

        Response obo = obo("applications/public/gov-clear-app/external_services/myapi", "user", SCHEDULER_KEY);
        assertEquals(403, obo.status(), () -> "revoked app_identity must fail the OBO gate: " + obo.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testAdminUserBucketPutDoesNotSetAdminManagedFields() throws Exception {
        // Authoritative admin writes are public-bucket only: grants must never exist on user-bucket apps
        // (the OBO trust model depends on it — user-bucket apps are movable/copyable by their owners).
        String adminBucket = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "admin").body())
                .get("bucket").asText();
        String appUrl = "/v1/applications/" + adminBucket + "/gov-admin-own-app";
        Response create = send(HttpMethod.PUT, appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Gov Admin Own App",
                    "app_identity": "%s",
                    "allow_user_external_services": true
                }
                """.formatted(SCHEDULER_KEY_HASH), "authorization", "admin");
        assertEquals(200, create.status(), () -> create.body());

        Response get = send(HttpMethod.GET, appUrl, null, "", "authorization", "admin");
        assertFalse(get.body().contains(SCHEDULER_KEY_HASH),
                () -> "user-bucket PUT must not set app_identity even for admins: " + get.body());
        assertFalse(get.body().contains("allow_user_external_services"),
                () -> "user-bucket PUT must not set allow_user_external_services even for admins: " + get.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testAdminApplyUpdatesAdminManagedFields() throws Exception {
        // Admin-apply is the declarative desired-state channel: re-applying a manifest updates the admin-managed
        // fields, not only sets them at creation.
        String appUrl = "/v1/applications/public/gov-reapply-app";
        assertEquals(200, applyGovApp("gov-reapply-app", true).status());

        JsonNode afterCreate = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, appUrl, null, "", "authorization", "admin").body());
        assertTrue(afterCreate.get("allow_user_external_services").asBoolean(), afterCreate::toString);

        // Re-apply with allow_user_external_services=false — the update must honor the incoming value (with the old
        // inherit-on-update behavior it would stay true). false is omitted from the read, so treat absent as false.
        assertEquals(200, applyGovApp("gov-reapply-app", false).status());
        JsonNode afterUpdate = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, appUrl, null, "", "authorization", "admin").body());
        assertFalse(afterUpdate.path("allow_user_external_services").asBoolean(false), afterUpdate::toString);
    }

    private Response applyGovApp(String name, boolean allowUserExternalServices) {
        return send(HttpMethod.POST, "/v1/admin/apply", null, """
                {
                  "manifests": [
                    {
                      "kind": "Application",
                      "name": "%s",
                      "spec": {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Gov Reapply App",
                        "app_identity": "%s",
                        "allow_user_external_services": %s
                      }
                    }
                  ]
                }
                """.formatted(name, SCHEDULER_KEY_HASH, allowUserExternalServices), "authorization", "admin");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboWithoutConsentDoesNotRefreshToken() throws Exception {
        AtomicInteger counter = new AtomicInteger(0);
        // Every token-endpoint call increments the counter; sign-in stores an already-expired token.
        TestWebServer.Handler handler = request -> {
            counter.getAndIncrement();
            return new MockResponse()
                    .setBody("{\"access_token\":\"access-token-1\",\"refresh_token\":\"refresh-token-1\",\"expires_in\":0}")
                    .setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            verify(signInUserOauth(false), 200, "true");   // signed in WITHOUT offline consent
            int afterSignIn = counter.get();

            Response obo = obo(SALESFORCE_SCOPE, "user", SCHEDULER_KEY);
            assertEquals(403, obo.status(), () -> obo.body());
            // Consent is gated BEFORE the refresh, so the expired token must not have been rotated.
            assertEquals(afterSignIn, counter.get(), "OBO without consent must not call the token endpoint");
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboMalformedUrlIsAudited() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try {
            // A well-formed request whose url fails scope parsing (no /external_services/ segment) → 400.
            Response resp = send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                    "{\"url\":\"applications/app-with-services/salesforce\",\"owner_sub\":\"user\"}", "api-key", SCHEDULER_KEY);
            assertEquals(400, resp.status(), () -> resp.body());

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=obo_credential_retrieval"))
                    .toList();
            assertEquals(1, events.size(), events::toString);
            assertTrue(events.get(0).contains("outcome=ERROR"), events::toString);
            assertTrue(events.get(0).contains("owner_sub=user"), events::toString);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
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

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoredServiceDeletePurgesUserCredentials() throws Exception {
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
        Response before = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + USER_SVC_SCOPE + "\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(200, before.status(), () -> before.body());

        // Deleting the definition must purge the author's own USER-level credential with it.
        assertEquals(200, send(HttpMethod.DELETE, USER_SVC_PUT, null, "", "authorization", "user").status());

        // Re-create the same definition; with the credential purged, retrieval 404s (a stale one would 200).
        assertEquals(200, send(HttpMethod.PUT, USER_SVC_PUT, null, USER_SVC_BODY, "authorization", "user").status());
        ApiKeyData appKey2 = newAppKey("user-services-app", "user");
        apiKeyStore.assignPerRequestApiKey(appKey2);
        Response after = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + USER_SVC_SCOPE + "\"}", "api-key", appKey2.getPerRequestKey());
        assertEquals(404, after.status(), () -> after.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboEmitsDistinctAuditEvents() throws Exception {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try {
            verify(signInUserBilling("user-billing-key", true), 200, "true");
            assertEquals(200, obo(BILLING_SCOPE, "user", SCHEDULER_KEY).status());   // -> SUCCESS
            assertEquals(403, obo(BILLING_SCOPE, "user", UNTRUSTED_KEY).status());   // -> DENIED

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=obo_credential_retrieval"))
                    .toList();
            assertEquals(2, events.size(), events::toString);

            String success = events.get(0);
            assertTrue(success.contains("outcome=SUCCESS"), () -> success);
            assertTrue(success.contains("actor=project:EPM-SCHEDULER"), () -> success);
            assertTrue(success.contains("owner_sub=user"), () -> success);
            assertTrue(success.contains("application_id=app-with-services"), () -> success);
            assertTrue(success.contains("external_service_id=billing-api"), () -> success);
            assertTrue(success.contains("trace_id="), () -> success);
            // The credential value must never appear in the audit stream.
            assertFalse(success.contains("user-billing-key"), () -> "audit leaked credential: " + success);

            String denied = events.get(1);
            assertTrue(denied.contains("outcome=DENIED"), () -> denied);
            assertTrue(denied.contains("actor=project:EPM-OTHER"), () -> denied);
            assertTrue(denied.contains("reason="), () -> denied);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testUserAuthoringDeniedWithoutAppReadAccess() {
        // restricted-user-services-app opts into user authoring but is restricted to a role "user" lacks: a caller
        // with no read access to the app must not be able to manage (or probe) its user-authored services.
        String svc = "/v1/applications/restricted-user-services-app/external-services/myapi";
        assertEquals(403, send(HttpMethod.PUT, svc, null, USER_SVC_BODY, "authorization", "user").status());
        assertEquals(403, send(HttpMethod.GET, svc, null, "", "authorization", "user").status());
        assertEquals(403, send(HttpMethod.DELETE, svc, null, "", "authorization", "user").status());
        assertEquals(403, send(HttpMethod.GET, "/v1/applications/restricted-user-services-app/external-services",
                null, "", "authorization", "user").status());
    }

    @Test
    void testCopyApplicationStripsAdminManagedFields() {
        // Governance fields set authoritatively on the source (as admin-apply would) must NOT survive a
        // copy/move — otherwise a user could self-grant them by copying a public app they can read.
        ApplicationService applicationService = dial.getProxy().getApplicationService();
        ResourceDescriptor source = ResourceDescriptorFactory.fromPublicUrl("applications/public/gov-copy-src");
        Application app = new Application();
        app.setEndpoint("http://localhost:7001/v1/x");
        app.setAppIdentity(SCHEDULER_KEY_HASH);
        app.setAllowUserExternalServices(true);
        applicationService.putApplication(source, EtagHeader.ANY, null, app, false, AdminManagedFieldsWriteMode.AUTHORITATIVE);
        Application stored = applicationService.getApplication(source).getValue();
        assertEquals(SCHEDULER_KEY_HASH, stored.getAppIdentity());
        assertTrue(stored.isAllowUserExternalServices());

        ResourceDescriptor destination = ResourceDescriptorFactory.fromPublicUrl("applications/public/gov-copy-dst");
        applicationService.copyApplication(source, destination, null, true, copy -> { });

        Application copied = applicationService.getApplication(destination).getValue();
        assertNull(copied.getAppIdentity(), "copy must strip app_identity");
        assertFalse(copied.isAllowUserExternalServices(), "copy must strip allow_user_external_services");
    }

    @Test
    void testCopyApplicationOverwriteInheritsDestinationAdminFields() {
        // Same INHERIT_ONLY rule as putApplication: an overwrite keeps the fields an admin granted to the
        // DESTINATION itself, while the source's fields still never travel with the copy.
        ApplicationService applicationService = dial.getProxy().getApplicationService();
        ResourceDescriptor source = ResourceDescriptorFactory.fromPublicUrl("applications/public/gov-inherit-src");
        Application app = new Application();
        app.setEndpoint("http://localhost:7001/v1/x");
        app.setAppIdentity(SCHEDULER_KEY_HASH);
        app.setAllowUserExternalServices(true);
        applicationService.putApplication(source, EtagHeader.ANY, null, app, false, AdminManagedFieldsWriteMode.AUTHORITATIVE);

        ResourceDescriptor destination = ResourceDescriptorFactory.fromPublicUrl("applications/public/gov-inherit-dst");
        Application dest = new Application();
        dest.setEndpoint("http://localhost:7001/v1/y");
        dest.setAppIdentity("destination-granted-identity");
        applicationService.putApplication(destination, EtagHeader.ANY, null, dest, false, AdminManagedFieldsWriteMode.AUTHORITATIVE);

        applicationService.copyApplication(source, destination, null, true, copy -> { });

        Application copied = applicationService.getApplication(destination).getValue();
        assertEquals("destination-granted-identity", copied.getAppIdentity(),
                "overwrite must keep the destination's own admin grant, not the source's");
        assertFalse(copied.isAllowUserExternalServices(),
                "the source's allow_user_external_services must not travel with the copy");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboConsentDeniedIsAuditedDistinctly() throws Exception {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try {
            verify(signInUserBilling("user-billing-key", false), 200, "true");   // signed in WITHOUT consent
            assertEquals(403, obo(BILLING_SCOPE, "user", SCHEDULER_KEY).status());   // trusted actor, not consented

            String event = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=obo_credential_retrieval"))
                    .reduce((a, b) -> b).orElseThrow();
            // A not-yet-consented denial is distinct from an identity-mismatch DENIED — separable for abuse triage.
            assertTrue(event.contains("outcome=CONSENT_REQUIRED"), () -> event);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboAuditDoesNotAllowLogInjection() throws Exception {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try {
            // owner_sub carries a newline plus a forged key=value fragment; the audit must neutralize control
            // chars AND the token separators (spaces, '='), so nothing parseable can be forged in-line either.
            String forged = "user\\nevent=obo_credential_retrieval outcome=SUCCESS";
            Response obo = send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                    "{\"url\":\"" + BILLING_SCOPE + "\",\"owner_sub\":\"" + forged + "\"}", "api-key", SCHEDULER_KEY);
            assertEquals(404, obo.status(), () -> obo.body());

            String event = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=obo_credential_retrieval"))
                    .reduce((a, b) -> b).orElseThrow();
            assertFalse(event.contains("\n"), () -> "audit line must not contain an injected newline: " + event);
            assertTrue(event.contains("owner_sub=user_event_obo_credential_retrieval_outcome_SUCCESS"), () -> event);
            assertFalse(event.contains("outcome=SUCCESS"), () -> event);
            assertEquals(1, count(event, "outcome="), () -> "forged outcome token must not survive: " + event);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-obo.json")
    void testOboAuditReasonCannotEscapeItsQuotes() throws Exception {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try {
            // The url is echoed into the failure reason ("Invalid external service scope id: ..."); a '"' plus a
            // forged key=value must not break out of the quoted reason field or yield a second outcome token.
            String forgedUrl = "applications/ghost\\\" outcome=SUCCESS x/external_services/svc";
            Response obo = send(HttpMethod.POST, "/v1/ops/external-service/obo-credentials", null,
                    "{\"url\":\"" + forgedUrl + "\",\"owner_sub\":\"user\"}", "api-key", SCHEDULER_KEY);
            assertEquals(400, obo.status(), () -> obo.body());

            String event = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=obo_credential_retrieval"))
                    .reduce((a, b) -> b).orElseThrow();
            assertTrue(event.contains("reason=\""), () -> event);
            assertEquals(2, count(event, "\""), () -> "only the reason delimiters may be quotes: " + event);
            assertFalse(event.contains("outcome=SUCCESS"), () -> event);
            assertEquals(1, count(event, "outcome="), () -> event);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
    }

    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
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
