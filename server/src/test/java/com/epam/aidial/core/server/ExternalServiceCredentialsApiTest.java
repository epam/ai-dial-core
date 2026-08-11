package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ExternalService;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.IdentityProvider;
import com.epam.aidial.core.server.service.AdminManagedFieldsWriteMode;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ExternalServiceCredentialsApiTest extends ResourceBaseTest {

    private static final String SALESFORCE_SCOPE = "applications/app-with-services/external_services/salesforce";
    private static final String BILLING_SCOPE = "applications/app-with-services/external_services/billing-api";
    private static final String DIAL_NATIVE_SCOPE = "applications/app-with-services/external_services/dial";

    private static final String OAUTH_TOKEN_RESPONSE = """
            {
                "access_token": "access-token-1",
                "refresh_token": "refresh-token-1",
                "expires_in": 3600
            }
            """;

    private static final String OAUTH_TOKEN_RESPONSE_REFRESHED = """
            {
                "access_token": "access-token-2",
                "refresh_token": "refresh-token-2",
                "expires_in": 3600
            }
            """;

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInUserOauthAndRetrieve() throws Exception {
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(OAUTH_TOKEN_RESPONSE)
                .setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(SALESFORCE_SCOPE), "authorization", "user");
            verify(signIn, 200, "true");

            ApiKeyData appKey = newAppKey("app-with-services", "user");
            apiKeyStore.assignPerRequestApiKey(appKey);

            Response credResp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                    "{\"url\":\"" + SALESFORCE_SCOPE + "\"}",
                    "api-key", appKey.getPerRequestKey());
            assertEquals(200, credResp.status(), () -> credResp.body());

            JsonNode body = ProxyUtil.MAPPER.readTree(credResp.body());
            assertEquals("Authorization", body.get("header_name").asText());
            assertEquals("Bearer access-token-1", body.get("header_value").asText());
            assertNotNull(body.get("expires_at"));

            Response signOut = send(HttpMethod.POST, "/v1/ops/external-service/signout", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH"
                    }
                    """.formatted(SALESFORCE_SCOPE), "authorization", "user");
            verify(signOut, 200, "true");

            ApiKeyData appKey2 = newAppKey("app-with-services", "user");
            apiKeyStore.assignPerRequestApiKey(appKey2);
            Response after = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                    "{\"url\":\"" + SALESFORCE_SCOPE + "\"}",
                    "api-key", appKey2.getPerRequestKey());
            assertEquals(404, after.status());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInUserApiKeyAndRetrieve() throws Exception {
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "secret-billing-key"
                }
                """.formatted(BILLING_SCOPE), "authorization", "user");
        verify(signIn, 200, "true");

        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);

        Response credResp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}",
                "api-key", appKey.getPerRequestKey());
        assertEquals(200, credResp.status(), () -> credResp.body());

        JsonNode body = ProxyUtil.MAPPER.readTree(credResp.body());
        assertEquals("X-API-Key", body.get("header_name").asText());
        assertEquals("secret-billing-key", body.get("header_value").asText());
        assertNull(body.get("expires_at"));
        assertFalse(credResp.body().contains("expires_at"));
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInApplicationLevelRequiresAdmin() {
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(OAUTH_TOKEN_RESPONSE)
                .setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response nonAdmin = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "APPLICATION",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(SALESFORCE_SCOPE), "authorization", "user");
            assertEquals(403, nonAdmin.status());

            Response admin = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "APPLICATION",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(SALESFORCE_SCOPE), "authorization", "admin");
            verify(admin, 200, "true");
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInWrongAuthType() {
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "x"
                }
                """.formatted(SALESFORCE_SCOPE), "authorization", "user");
        assertEquals(400, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInUnknownExternalService() {
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "applications/app-with-services/external_services/unknown",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "x"
                }
                """, "authorization", "user");
        assertEquals(404, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalRequiresPerRequestKey() {
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}", "authorization", "user");
        assertEquals(401, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalForbidsCrossApp() {
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "secret"
                }
                """.formatted(BILLING_SCOPE), "authorization", "user");
        verify(signIn, 200, "true");

        ApiKeyData otherKey = newAppKey("other-app", "user");
        apiKeyStore.assignPerRequestApiKey(otherKey);

        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}",
                "api-key", otherKey.getPerRequestKey());
        assertEquals(403, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalUserPreferredOverApplication() throws Exception {
        Response appSign = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared-key"
                }
                """.formatted(BILLING_SCOPE), "authorization", "admin");
        verify(appSign, 200, "true");

        Response userSign = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "user-key"
                }
                """.formatted(BILLING_SCOPE), "authorization", "user");
        verify(userSign, 200, "true");

        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);

        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}",
                "api-key", appKey.getPerRequestKey());
        assertEquals(200, resp.status(), () -> resp.body());
        JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
        assertEquals("user-key", body.get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalFallsBackToApplicationLevel() throws Exception {
        Response appSign = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared-key"
                }
                """.formatted(BILLING_SCOPE), "authorization", "admin");
        verify(appSign, 200, "true");

        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);

        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}",
                "api-key", appKey.getPerRequestKey());
        assertEquals(200, resp.status(), () -> resp.body());
        JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
        assertEquals("shared-key", body.get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalNotFound() {
        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);

        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}",
                "api-key", appKey.getPerRequestKey());
        assertEquals(404, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testOauthTokenRefresh() throws Exception {
        java.util.concurrent.atomic.AtomicInteger counter = new java.util.concurrent.atomic.AtomicInteger(0);
        // First call (sign-in) returns expires_in=0 to force a refresh on retrieval.
        // Subsequent calls (refresh) return the new token with a long lifetime.
        TestWebServer.Handler handler = request -> {
            int call = counter.getAndIncrement();
            String body = call == 0
                    ? """
                    {
                        "access_token": "access-token-1",
                        "refresh_token": "refresh-token-1",
                        "expires_in": 0
                    }
                    """
                    : OAUTH_TOKEN_RESPONSE_REFRESHED;
            return new MockResponse().setBody(body).setHeader("Content-Type", "application/json");
        };

        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(SALESFORCE_SCOPE), "authorization", "user");
            verify(signIn, 200, "true");

            ApiKeyData appKey = newAppKey("app-with-services", "user");
            apiKeyStore.assignPerRequestApiKey(appKey);

            Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                    "{\"url\":\"" + SALESFORCE_SCOPE + "\"}",
                    "api-key", appKey.getPerRequestKey());
            assertEquals(200, resp.status(), () -> resp.body());

            JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
            assertEquals("Bearer access-token-2", body.get("header_value").asText());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDynamicApplicationFlow() throws Exception {
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        assertEquals(200, bucketResp.status());
        String userBucket = ProxyUtil.MAPPER.readTree(bucketResp.body()).get("bucket").asText();
        String appUrl = "applications/" + userBucket + "/dynamic-app";
        Response createApp = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Dynamic App",
                    "external_services": {
                        "billing-api": {
                            "auth_settings": {
                                "authentication_type": "API_KEY",
                                "api_key_header": "X-API-Key"
                            }
                        }
                    }
                }
                """, "authorization", "user");
        assertEquals(200, createApp.status(), () -> createApp.body());

        String scope = appUrl + "/external_services/billing-api";

        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "dyn-key"
                }
                """.formatted(scope), "authorization", "user");
        verify(signIn, 200, "true");

        ApiKeyData appKey = newAppKey(appUrl, "user");
        apiKeyStore.assignPerRequestApiKey(appKey);

        Response credResp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + scope + "\"}",
                "api-key", appKey.getPerRequestKey());
        assertEquals(200, credResp.status(), () -> credResp.body());
        JsonNode body = ProxyUtil.MAPPER.readTree(credResp.body());
        assertEquals("X-API-Key", body.get("header_name").asText());
        assertEquals("dyn-key", body.get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testAppOwnerCanSignInApplicationLevelForDynamicApp() throws Exception {
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        assertEquals(200, bucketResp.status());
        String userBucket = ProxyUtil.MAPPER.readTree(bucketResp.body()).get("bucket").asText();
        String appUrl = "applications/" + userBucket + "/owned-app";
        Response createApp = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Owned App",
                    "external_services": {
                        "billing-api": {
                            "auth_settings": {
                                "authentication_type": "API_KEY",
                                "api_key_header": "X-API-Key"
                            }
                        }
                    }
                }
                """, "authorization", "user");
        assertEquals(200, createApp.status(), () -> createApp.body());

        String scope = appUrl + "/external_services/billing-api";
        Response appSignIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared-dyn-key"
                }
                """.formatted(scope), "authorization", "user");
        verify(appSignIn, 200, "true");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testNonOwnerCannotSignInApplicationLevelForDynamicApp() throws Exception {
        // admin creates a dynamic app in admin's own bucket
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "admin");
        assertEquals(200, bucketResp.status());
        String adminBucket = ProxyUtil.MAPPER.readTree(bucketResp.body()).get("bucket").asText();
        String appUrl = "applications/" + adminBucket + "/admin-owned-app";
        Response createApp = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Admin Owned App",
                    "external_services": {
                        "billing-api": {
                            "auth_settings": {
                                "authentication_type": "API_KEY",
                                "api_key_header": "X-API-Key"
                            }
                        }
                    }
                }
                """, "authorization", "admin");
        assertEquals(200, createApp.status(), () -> createApp.body());

        String scope = appUrl + "/external_services/billing-api";
        // "user" is not admin and has no write access to admin's app → 403
        Response appSignIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "x"
                }
                """.formatted(scope), "authorization", "user");
        assertEquals(403, appSignIn.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInRejectedWithPerRequestKey() {
        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "x"
                }
                """.formatted(BILLING_SCOPE), "api-key", appKey.getPerRequestKey());
        assertEquals(401, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignOutRejectedWithPerRequestKey() {
        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/signout", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY"
                }
                """.formatted(BILLING_SCOPE), "api-key", appKey.getPerRequestKey());
        assertEquals(401, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testPutDynamicApplicationWithInvalidExternalServiceRejected() throws Exception {
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", "user");
        assertEquals(200, bucketResp.status());
        String userBucket = ProxyUtil.MAPPER.readTree(bucketResp.body()).get("bucket").asText();
        String appUrl = "applications/" + userBucket + "/bad-app";
        // OAUTH missing token_endpoint and authorization_endpoint → validator should reject
        Response createApp = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Bad App",
                    "external_services": {
                        "broken": {
                            "auth_settings": {
                                "authentication_type": "OAUTH",
                                "client_id": "cid"
                            }
                        }
                    }
                }
                """, "authorization", "user");
        assertEquals(400, createApp.status(), () -> createApp.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials-bad.json")
    void testStaticConfigDropsInvalidExternalServiceKeepsValidOnes() throws Exception {
        // Valid billing-api still works, invalid oauth-broken has been dropped at load.
        Response signInGood = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "applications/app-with-services/external_services/billing-api",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "k"
                }
                """, "authorization", "user");
        verify(signInGood, 200, "true");

        Response signInBad = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "applications/app-with-services/external_services/oauth-broken",
                    "credentials_level": "USER",
                    "authentication_type": "OAUTH",
                    "code": "c"
                }
                """, "authorization", "user");
        assertEquals(404, signInBad.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDynamicAppOauthSecretEncryptedAtRestAndDecryptedOnSignIn() throws Exception {
        String plaintextSecret = "dyn-plaintext-secret";
        java.util.concurrent.atomic.AtomicReference<String> tokenBody = new java.util.concurrent.atomic.AtomicReference<>();
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/token", request -> {
                tokenBody.set(request.getBody().readUtf8());
                return new MockResponse().setBody(OAUTH_TOKEN_RESPONSE).setHeader("Content-Type", "application/json");
            });

            String appUrl = createDynamicOauthApp("user", "dyn-oauth-app", plaintextSecret);
            String scope = appUrl + "/external_services/salesforce";

            // The raw resource GET strips secret material, so client_secret is absent. Encryption-at-rest
            // is proven by the runtime decrypt below (a plaintext-at-rest value would not decrypt).
            Response rawApp = send(HttpMethod.GET, "/v1/" + appUrl, null, "", "authorization", "user");
            assertEquals(200, rawApp.status(), () -> rawApp.body());
            JsonNode storedAuth = ProxyUtil.MAPPER.readTree(rawApp.body())
                    .get("external_services").get("salesforce").get("auth_settings");
            assertNull(storedAuth.get("client_secret"), () -> "raw GET must not expose client_secret: " + rawApp.body());
            assertFalse(rawApp.body().contains(plaintextSecret), "plaintext client_secret must not appear");

            // Decryption at runtime: sign-in exchanges the code using the DECRYPTED client_secret.
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(scope), "authorization", "user");
            verify(signIn, 200, "true");

            assertNotNull(tokenBody.get(), "token endpoint should have been called on sign-in");
            assertTrue(tokenBody.get().contains("client_secret=" + plaintextSecret),
                    "token exchange must send decrypted client_secret, got: " + tokenBody.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDynamicAppOauthSecretPreservedWhenOmittedOnUpdate() throws Exception {
        String plaintextSecret = "preserved-secret";
        java.util.concurrent.atomic.AtomicReference<String> tokenBody = new java.util.concurrent.atomic.AtomicReference<>();
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/token", request -> {
                tokenBody.set(request.getBody().readUtf8());
                return new MockResponse().setBody(OAUTH_TOKEN_RESPONSE).setHeader("Content-Type", "application/json");
            });

            String appUrl = createDynamicOauthApp("user", "dyn-omit-app", plaintextSecret);

            // Update the app WITHOUT client_secret — it must be preserved, not wiped.
            Response update = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                    {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Renamed App",
                        "external_services": {
                            "salesforce": {
                                "auth_settings": {
                                    "authentication_type": "OAUTH",
                                    "client_id": "dyn-client-id",
                                    "authorization_endpoint": "http://localhost:9876/authorize",
                                    "token_endpoint": "http://localhost:9876/token",
                                    "redirect_uri": "http://localhost:3000/auth/signin",
                                    "token_endpoint_auth_method": "client_secret_post"
                                }
                            }
                        }
                    }
                    """, "authorization", "user");
            assertEquals(200, update.status(), () -> update.body());

            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s/external_services/salesforce",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(appUrl), "authorization", "user");
            verify(signIn, 200, "true");

            assertNotNull(tokenBody.get());
            assertTrue(tokenBody.get().contains("client_secret=" + plaintextSecret),
                    "omitted client_secret must be preserved from previous version, got: " + tokenBody.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDynamicAppOauthSecretReEncryptedOnCopy() throws Exception {
        String plaintextSecret = "copied-secret";
        java.util.concurrent.atomic.AtomicReference<String> tokenBody = new java.util.concurrent.atomic.AtomicReference<>();
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/token", request -> {
                tokenBody.set(request.getBody().readUtf8());
                return new MockResponse().setBody(OAUTH_TOKEN_RESPONSE).setHeader("Content-Type", "application/json");
            });

            String sourceUrl = createDynamicOauthApp("user", "copy-src-app", plaintextSecret);
            String bucket = sourceUrl.split("/")[1];
            String destUrl = "applications/" + bucket + "/copy-dst-app";

            Response copy = send(HttpMethod.POST, "/v1/ops/resource/copy", null, """
                    {
                        "sourceUrl": "%s",
                        "destinationUrl": "%s"
                    }
                    """.formatted(sourceUrl, destUrl), "authorization", "user");
            assertEquals(200, copy.status(), () -> copy.body());

            // The copy is re-encrypted under the destination's bucket/path. If it were not, the
            // destination secret would be undecryptable here and sign-in would fail.
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s/external_services/salesforce",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(destUrl), "authorization", "user");
            verify(signIn, 200, "true");

            assertNotNull(tokenBody.get());
            assertTrue(tokenBody.get().contains("client_secret=" + plaintextSecret),
                    "copied app must decrypt to the original client_secret, got: " + tokenBody.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testExternalServicesPreservedWhenFieldOmittedOnAppUpdate() throws Exception {
        String plaintextSecret = "preserved-on-omit-secret";
        java.util.concurrent.atomic.AtomicReference<String> tokenBody = new java.util.concurrent.atomic.AtomicReference<>();
        try (TestWebServer server = new TestWebServer(9876)) {
            server.map(HttpMethod.POST, "/token", request -> {
                tokenBody.set(request.getBody().readUtf8());
                return new MockResponse().setBody(OAUTH_TOKEN_RESPONSE).setHeader("Content-Type", "application/json");
            });

            String appUrl = createDynamicOauthApp("user", "dyn-omit-field-app", plaintextSecret);

            // Update the app WITHOUT the external_services field at all (e.g. a config editor saving other
            // properties). The stored services must be preserved, not wiped.
            Response update = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                    {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "Renamed Without External Services"
                    }
                    """, "authorization", "user");
            assertEquals(200, update.status(), () -> update.body());

            // The service still appears on the raw GET (secret stripped, definition intact).
            Response rawApp = send(HttpMethod.GET, "/v1/" + appUrl, null, "", "authorization", "user");
            assertEquals(200, rawApp.status(), () -> rawApp.body());
            JsonNode es = ProxyUtil.MAPPER.readTree(rawApp.body()).get("external_services");
            assertNotNull(es, () -> "omitted field must preserve stored services: " + rawApp.body());
            assertNotNull(es.get("salesforce"), () -> "salesforce service must survive an omitted-field update: " + rawApp.body());
            assertEquals("Renamed Without External Services",
                    ProxyUtil.MAPPER.readTree(rawApp.body()).get("display_name").asText());

            // The encrypted-at-rest client_secret survived too: a fresh sign-in still exchanges the original secret.
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s/external_services/salesforce",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(appUrl), "authorization", "user");
            verify(signIn, 200, "true");
            assertNotNull(tokenBody.get());
            assertTrue(tokenBody.get().contains("client_secret=" + plaintextSecret),
                    "preserved client_secret must still be usable after an omitted-field update, got: " + tokenBody.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testExternalServicesRemovedWhenExplicitEmptyMapOnAppUpdate() throws Exception {
        String appUrl = createDynamicOauthApp("user", "dyn-explicit-empty-app", "to-be-removed-secret");

        // An explicit empty map is a deliberate change and must remove the service (unlike an omitted field).
        Response update = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Cleared",
                    "external_services": {}
                }
                """, "authorization", "user");
        assertEquals(200, update.status(), () -> update.body());

        Response rawApp = send(HttpMethod.GET, "/v1/" + appUrl, null, "", "authorization", "user");
        assertEquals(200, rawApp.status(), () -> rawApp.body());
        JsonNode es = ProxyUtil.MAPPER.readTree(rawApp.body()).get("external_services");
        // NON_EMPTY serialization omits an empty map entirely.
        assertNull(es, () -> "explicit empty map must remove services: " + rawApp.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testGetApplicationExposesExternalServicesWithStatusAndNoSecret() throws Exception {
        // A regular user (not admin/owner) fetches the app deployment and must see its external
        // services plus their own sign-in status, so a UI can render connect/connected — without
        // any credential material in the response.
        Response before = send(HttpMethod.GET, "/openai/applications/app-with-services", null, "", "authorization", "user");
        assertEquals(200, before.status(), () -> before.body());
        JsonNode es = ProxyUtil.MAPPER.readTree(before.body()).get("external_services");
        assertNotNull(es, () -> "app GET must include external_services: " + before.body());
        JsonNode billing = es.get("billing-api").get("auth_settings");
        assertNull(billing.get("client_secret"), "no client_secret in app GET");
        assertEquals("SIGNED_OUT", billing.get("user_level_auth_status").asText());
        assertNotNull(billing.get("app_level_auth_status"));
        // salesforce is OAUTH with a client_secret in config — must be stripped from the app view.
        assertFalse(before.body().contains("test-client-secret"), "OAUTH client_secret must be stripped");

        // After this user signs in, their status flips to SIGNED_IN.
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "applications/app-with-services/external_services/billing-api",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "k"
                }
                """, "authorization", "user");
        verify(signIn, 200, "true");

        Response after = send(HttpMethod.GET, "/openai/applications/app-with-services", null, "", "authorization", "user");
        assertEquals("SIGNED_IN", ProxyUtil.MAPPER.readTree(after.body())
                .get("external_services").get("billing-api").get("auth_settings").get("user_level_auth_status").asText());
    }

    private String createDynamicOauthApp(String role, String appName, String clientSecret) throws Exception {
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", role);
        assertEquals(200, bucketResp.status());
        String bucket = ProxyUtil.MAPPER.readTree(bucketResp.body()).get("bucket").asText();
        String appUrl = "applications/" + bucket + "/" + appName;
        Response createApp = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Dyn OAuth App",
                    "external_services": {
                        "salesforce": {
                            "auth_settings": {
                                "authentication_type": "OAUTH",
                                "client_id": "dyn-client-id",
                                "client_secret": "%s",
                                "authorization_endpoint": "http://localhost:9876/authorize",
                                "token_endpoint": "http://localhost:9876/token",
                                "redirect_uri": "http://localhost:3000/auth/signin",
                                "token_endpoint_auth_method": "client_secret_post"
                            }
                        }
                    }
                }
                """.formatted(clientSecret), "authorization", role);
        assertEquals(200, createApp.status(), () -> createApp.body());
        return appUrl;
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testAdminApplyEncryptsExternalServiceSecretAndIsUsable() throws Exception {
        // Guards the config-admin write path (POST /v1/admin/apply): applications route through
        // ApplicationService.putApplication, so external-service secrets must be encrypted at rest
        // and decryptable at runtime, exactly like the resource-PUT path.
        String plaintextSecret = "applied-plaintext-secret";
        java.util.concurrent.atomic.AtomicReference<String> tokenBody = new java.util.concurrent.atomic.AtomicReference<>();
        TestWebServer.Handler applyHandler = request -> {
            tokenBody.set(request.getBody().readUtf8());
            return new MockResponse().setBody(OAUTH_TOKEN_RESPONSE).setHeader("Content-Type", "application/json");
        };
        try (TestWebServer ignore = new TestWebServer(9876, applyHandler)) {
            String apply = """
                    {
                      "manifests": [
                        {
                          "kind": "Application",
                          "name": "applications/public/applied-ext-svc-app",
                          "spec": {
                            "endpoint": "http://localhost:7001/v1/x",
                            "display_name": "Applied App",
                            "external_services": {
                              "salesforce": {
                                "auth_settings": {
                                  "authentication_type": "OAUTH",
                                  "client_id": "cid",
                                  "client_secret": "%s",
                                  "authorization_endpoint": "http://localhost:9876/authorize",
                                  "token_endpoint": "http://localhost:9876/token",
                                  "token_endpoint_auth_method": "client_secret_post"
                                }
                              }
                            }
                          }
                        }
                      ]
                    }
                    """.formatted(plaintextSecret);
            Response applyResp = send(HttpMethod.POST, "/v1/admin/apply", null, apply, "authorization", "admin");
            assertEquals(200, applyResp.status(), () -> applyResp.body());
            assertEquals("APPLIED",
                    ProxyUtil.MAPPER.readTree(applyResp.body()).get("results").get(0).get("status").asText());

            // The raw resource GET strips secret material (mirrors toolset clearAuthSettings), so neither
            // the encrypted nor the plaintext client_secret is exposed via the API. Encryption-at-rest is
            // proven below by the runtime decrypt: a plaintext-at-rest value would fail to decrypt and the
            // token endpoint would not receive the original secret.
            Response rawApp = send(HttpMethod.GET, "/v1/applications/public/applied-ext-svc-app",
                    null, "", "authorization", "admin");
            assertEquals(200, rawApp.status(), () -> rawApp.body());
            JsonNode storedAuth = ProxyUtil.MAPPER.readTree(rawApp.body())
                    .get("external_services").get("salesforce").get("auth_settings");
            assertNull(storedAuth.get("client_secret"), () -> "raw GET must not expose client_secret: " + rawApp.body());
            assertFalse(rawApp.body().contains(plaintextSecret), "plaintext must not be exposed");

            // Usable at runtime: OAUTH sign-in decrypts and forwards the plaintext to the token endpoint.
            String scope = "applications/public/applied-ext-svc-app/external_services/salesforce";
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(scope), "authorization", "admin");
            verify(signIn, 200, "true");
            assertNotNull(tokenBody.get(), "token endpoint should have been called on sign-in");
            assertTrue(tokenBody.get().contains("client_secret=" + plaintextSecret),
                    "admin-applied secret must decrypt at runtime, got: " + tokenBody.get());
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalDoesNotExposeRefreshTokenOrSecret() throws Exception {
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(OAUTH_TOKEN_RESPONSE).setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(SALESFORCE_SCOPE), "authorization", "user");
            verify(signIn, 200, "true");

            ApiKeyData appKey = newAppKey("app-with-services", "user");
            apiKeyStore.assignPerRequestApiKey(appKey);
            Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                    "{\"url\":\"" + SALESFORCE_SCOPE + "\"}", "api-key", appKey.getPerRequestKey());
            assertEquals(200, resp.status(), () -> resp.body());

            // Only the access token is exposed — never the refresh token or client secret.
            assertEquals("Bearer access-token-1",
                    ProxyUtil.MAPPER.readTree(resp.body()).get("header_value").asText());
            assertFalse(resp.body().contains("refresh-token-1"), "refresh_token must not be exposed");
            assertFalse(resp.body().contains("refresh_token"), "refresh_token field must not appear");
            assertFalse(resp.body().contains("test-client-secret"), "client_secret must not be exposed");
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalCrossUserUserCredentialIsolation() throws Exception {
        // "user" stores a USER-level credential (in user's own bucket).
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "user-secret"
                }
                """.formatted(BILLING_SCOPE), "authorization", "user");
        verify(signIn, 200, "true");

        // A per-request key for the SAME app but a DIFFERENT user identity must not see it.
        ApiKeyData intruderKey = newAppKey("app-with-services", "intruder");
        apiKeyStore.assignPerRequestApiKey(intruderKey);
        Response intruder = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}", "api-key", intruderKey.getPerRequestKey());
        assertEquals(404, intruder.status(), "another user's per-request key must not read USER credentials");

        // Control: the owning user's per-request key sees it.
        ApiKeyData ownerKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(ownerKey);
        Response owner = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}", "api-key", ownerKey.getPerRequestKey());
        assertEquals(200, owner.status(), () -> owner.body());
        assertEquals("user-secret", ProxyUtil.MAPPER.readTree(owner.body()).get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalExpiresAtIsUnixEpochSeconds() throws Exception {
        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody(OAUTH_TOKEN_RESPONSE).setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                    {
                        "url": "%s",
                        "credentials_level": "USER",
                        "authentication_type": "OAUTH",
                        "code": "auth-code"
                    }
                    """.formatted(SALESFORCE_SCOPE), "authorization", "user");

            ApiKeyData appKey = newAppKey("app-with-services", "user");
            apiKeyStore.assignPerRequestApiKey(appKey);
            Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                    "{\"url\":\"" + SALESFORCE_SCOPE + "\"}", "api-key", appKey.getPerRequestKey());
            assertEquals(200, resp.status(), () -> resp.body());

            long expiresAt = ProxyUtil.MAPPER.readTree(resp.body()).get("expires_at").asLong();
            // Must be Unix epoch SECONDS (not millis): a seconds value sits ~1.7e9; a millis bug ~1.7e12.
            assertTrue(expiresAt > 1_700_000_000L && expiresAt < 100_000_000_000L,
                    "expires_at must be Unix epoch seconds, got: " + expiresAt);
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInMissingUrlRejected() {
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "k"
                }
                """, "authorization", "user");
        assertEquals(400, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalMissingUrlRejected() {
        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{}", "api-key", appKey.getPerRequestKey());
        assertEquals(400, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testRetrievalMalformedUrlRejected() {
        ApiKeyData appKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response resp = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"not-an-external-service-scope\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(400, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignOutCannotDeleteOtherUsersCredentials() {
        // "user" stores a USER-level credential.
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "user-secret"
                }
                """.formatted(BILLING_SCOPE), "authorization", "user");
        verify(signIn, 200, "true");

        // "admin" (different identity) cannot remove it — USER creds live in the caller's own bucket.
        Response otherSignOut = send(HttpMethod.POST, "/v1/ops/external-service/signout", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY"
                }
                """.formatted(BILLING_SCOPE), "authorization", "admin");
        assertTrue(otherSignOut.status() >= 400, "another user's sign-out must not succeed");

        // "user"'s credential survived.
        ApiKeyData ownerKey = newAppKey("app-with-services", "user");
        apiKeyStore.assignPerRequestApiKey(ownerKey);
        Response owner = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + BILLING_SCOPE + "\"}", "api-key", ownerKey.getPerRequestKey());
        assertEquals(200, owner.status(), () -> owner.body());
    }

    // ---------------------------------------------------------------------------------------------
    // Management API (§6.5): GET/PUT/DELETE /v1/applications/{app_id}/external-services[/{id}]
    // ---------------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testListExternalServicesStaticAppAsAdmin() throws Exception {
        Response resp = send(HttpMethod.GET, "/v1/applications/app-with-services/external-services",
                null, "", "authorization", "admin");
        assertEquals(200, resp.status(), () -> resp.body());
        JsonNode arr = ProxyUtil.MAPPER.readTree(resp.body());
        assertTrue(arr.isArray());
        assertEquals(3, arr.size());
        assertFalse(resp.body().contains("test-client-secret"), "client_secret must not be leaked in list");
        for (JsonNode node : arr) {
            assertNotNull(node.get("id"));
            JsonNode authSettings = node.get("auth_settings");
            assertNull(authSettings.get("client_secret"), "client_secret must not be present");
            assertNotNull(authSettings.get("user_level_auth_status"));
            assertNotNull(authSettings.get("app_level_auth_status"));
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testGetExternalServiceStaticAppAsAdmin() throws Exception {
        Response resp = send(HttpMethod.GET, "/v1/applications/app-with-services/external-services/salesforce",
                null, "", "authorization", "admin");
        assertEquals(200, resp.status(), () -> resp.body());
        JsonNode node = ProxyUtil.MAPPER.readTree(resp.body());
        assertEquals("salesforce", node.get("id").asText());
        assertEquals("OAUTH", node.get("auth_settings").get("authentication_type").asText());
        assertNull(node.get("auth_settings").get("client_secret"));
        assertEquals("SIGNED_OUT", node.get("auth_settings").get("user_level_auth_status").asText());
        assertEquals("SIGNED_OUT", node.get("auth_settings").get("app_level_auth_status").asText());
        assertFalse(resp.body().contains("test-client-secret"));
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testGetUnknownExternalServiceReturns404() {
        Response resp = send(HttpMethod.GET, "/v1/applications/app-with-services/external-services/unknown",
                null, "", "authorization", "admin");
        assertEquals(404, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testListExternalServicesForbiddenForNonAdminOnStaticApp() {
        Response resp = send(HttpMethod.GET, "/v1/applications/app-with-services/external-services",
                null, "", "authorization", "user");
        assertEquals(403, resp.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testPutOnStaticAppRejected() {
        Response resp = send(HttpMethod.PUT, "/v1/applications/app-with-services/external-services/new-svc",
                null, """
                {
                    "auth_settings": {
                        "authentication_type": "API_KEY",
                        "api_key_header": "X-API-Key"
                    }
                }
                """, "authorization", "admin");
        assertEquals(400, resp.status(), () -> resp.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testPutDynamicExternalServiceCreatesUsableDefinition() throws Exception {
        String appUrl = createPlainDynamicApp("user", "mgmt-app");

        Response put = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/billing-api", null, """
                {
                    "display_name": "Billing",
                    "auth_settings": {
                        "authentication_type": "API_KEY",
                        "api_key_header": "X-API-Key"
                    }
                }
                """, "authorization", "user");
        assertEquals(200, put.status(), () -> put.body());

        // Now usable end-to-end: sign in and retrieve.
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s/external_services/billing-api",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "k"
                }
                """.formatted(appUrl), "authorization", "user");
        verify(signIn, 200, "true");

        ApiKeyData appKey = newAppKey(appUrl, "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response retrieve = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + appUrl + "/external_services/billing-api\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(200, retrieve.status(), () -> retrieve.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testPutDynamicExternalServiceValidationRejected() throws Exception {
        String appUrl = createPlainDynamicApp("user", "mgmt-bad-app");
        // OAUTH missing token_endpoint / authorization_endpoint → 400
        Response put = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/broken", null, """
                {
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid"
                    }
                }
                """, "authorization", "user");
        assertEquals(400, put.status(), () -> put.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testOauthExternalServiceRequiresClientSecretOnCreateOnly() throws Exception {
        String appUrl = createPlainDynamicApp("user", "mgmt-nosecret-app");

        // Management API: first-time OAUTH create without client_secret → 400 (CREATE_STATIC_CLIENT).
        Response mgmtCreate = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/salesforce", null, """
                {
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876/token"
                    }
                }
                """, "authorization", "user");
        assertEquals(400, mgmtCreate.status(), () -> mgmtCreate.body());

        // App PUT: a brand-new OAUTH service without client_secret is likewise rejected.
        Response appPut = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Mgmt App",
                    "external_services": {
                        "salesforce": {
                            "auth_settings": {
                                "authentication_type": "OAUTH",
                                "client_id": "cid",
                                "authorization_endpoint": "http://localhost:9876/authorize",
                                "token_endpoint": "http://localhost:9876/token"
                            }
                        }
                    }
                }
                """, "authorization", "user");
        assertEquals(400, appPut.status(), () -> appPut.body());

        // Create with client_secret succeeds...
        Response create = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/salesforce", null, """
                {
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid",
                        "client_secret": "the-secret",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876/token"
                    }
                }
                """, "authorization", "user");
        assertEquals(200, create.status(), () -> create.body());

        // ...and a subsequent update may omit client_secret (preserve-on-omit, NO_CLIENT_CHANGES).
        Response update = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/salesforce", null, """
                {
                    "display_name": "Salesforce",
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876/token"
                    }
                }
                """, "authorization", "user");
        assertEquals(200, update.status(), () -> update.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testPutDynamicExternalServiceSecretEncryptedAndHidden() throws Exception {
        String appUrl = createPlainDynamicApp("user", "mgmt-secret-app");
        String plaintextSecret = "mgmt-plaintext-secret";

        Response put = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/salesforce", null, """
                {
                    "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "cid",
                        "client_secret": "%s",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876/token"
                    }
                }
                """.formatted(plaintextSecret), "authorization", "user");
        assertEquals(200, put.status(), () -> put.body());
        // Management response must not expose the secret.
        assertFalse(put.body().contains(plaintextSecret), "PUT response must not contain client_secret");

        // Raw stored app: secret material stripped on read, no plaintext exposed (encryption-at-rest
        // for this put path is covered by testDynamicAppOauthSecretEncryptedAtRestAndDecryptedOnSignIn).
        Response rawApp = send(HttpMethod.GET, "/v1/" + appUrl, null, "", "authorization", "user");
        assertEquals(200, rawApp.status());
        JsonNode storedAuth = ProxyUtil.MAPPER.readTree(rawApp.body())
                .get("external_services").get("salesforce").get("auth_settings");
        assertNull(storedAuth.get("client_secret"), "raw GET must not expose client_secret");
        assertFalse(rawApp.body().contains(plaintextSecret));

        // Management GET must hide the secret.
        Response mgmtGet = send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/salesforce",
                null, "", "authorization", "user");
        assertEquals(200, mgmtGet.status());
        assertFalse(mgmtGet.body().contains(plaintextSecret));
        assertNull(ProxyUtil.MAPPER.readTree(mgmtGet.body()).get("auth_settings").get("client_secret"));
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDeleteDynamicExternalServiceRemovesDefinitionAndCascadesAppCredentials() throws Exception {
        String appUrl = createPlainDynamicApp("user", "mgmt-del-app");
        String scope = appUrl + "/external_services/billing-api";

        Response put = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/billing-api", null, """
                {
                    "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-API-Key" }
                }
                """, "authorization", "user");
        assertEquals(200, put.status(), () -> put.body());

        // Owner stores APP-level credentials.
        Response appSignIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared"
                }
                """.formatted(scope), "authorization", "user");
        verify(appSignIn, 200, "true");

        // Delete the definition.
        Response del = send(HttpMethod.DELETE, "/v1/" + appUrl + "/external-services/billing-api",
                null, "", "authorization", "user");
        assertEquals(200, del.status(), () -> del.body());

        // Definition gone.
        Response getAfter = send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/billing-api",
                null, "", "authorization", "user");
        assertEquals(404, getAfter.status());

        // Re-create the definition; the cascaded APP-level credential must NOT survive → retrieval 404.
        Response rePut = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/billing-api", null, """
                {
                    "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-API-Key" }
                }
                """, "authorization", "user");
        assertEquals(200, rePut.status(), () -> rePut.body());

        ApiKeyData appKey = newAppKey(appUrl, "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response retrieve = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + scope + "\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(404, retrieve.status(), "APP-level credentials should have been cascaded on delete");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testAppPutRemovingExternalServiceCascadesAppCredentials() throws Exception {
        // Removing a service via an explicit external_services map that drops it must purge its orphaned
        // APP-level credentials, like the dedicated DELETE — otherwise a same-id re-create inherits stale
        // creds. An OMITTED field preserves instead (testExternalServicesPreservedWhenFieldOmittedOnAppUpdate).
        String appUrl = createAppWithBillingService("user", "appput-del-app");
        String scope = appUrl + "/external_services/billing-api";

        Response appSignIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared"
                }
                """.formatted(scope), "authorization", "user");
        verify(appSignIn, 200, "true");

        // Drop the service via an explicit (empty) external_services map — a deliberate removal.
        Response removed = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Appput Del App",
                    "external_services": {}
                }
                """, "authorization", "user");
        assertEquals(200, removed.status(), () -> removed.body());

        // Re-create the SAME service; the cascaded APP-level credential must NOT survive → retrieval 404.
        Response readd = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Appput Del App",
                    "external_services": {
                        "billing-api": {
                            "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-API-Key" }
                        }
                    }
                }
                """, "authorization", "user");
        assertEquals(200, readd.status(), () -> readd.body());

        ApiKeyData appKey = newAppKey(appUrl, "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response retrieve = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + scope + "\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(404, retrieve.status(), "APP-level credentials must be purged when the service is removed via app PUT");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testAppPutKeepingExternalServicePreservesAppCredentials() throws Exception {
        // Guard against over-purging: an ordinary app update that KEEPS the service must not drop its
        // APP-level credentials.
        String appUrl = createAppWithBillingService("user", "appput-keep-app");
        String scope = appUrl + "/external_services/billing-api";

        Response appSignIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared"
                }
                """.formatted(scope), "authorization", "user");
        verify(appSignIn, 200, "true");

        // Update the app (rename) while keeping the same external service.
        Response update = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Renamed Keep App",
                    "external_services": {
                        "billing-api": {
                            "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-API-Key" }
                        }
                    }
                }
                """, "authorization", "user");
        assertEquals(200, update.status(), () -> update.body());

        ApiKeyData appKey = newAppKey(appUrl, "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response retrieve = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + scope + "\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(200, retrieve.status(), () -> retrieve.body());
        assertEquals("shared", ProxyUtil.MAPPER.readTree(retrieve.body()).get("header_value").asText());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDeleteApplicationCascadesAppCredentials() throws Exception {
        // Deleting the whole application drops every external service, so its APP-level credentials must be
        // purged too — re-creating an app at the same url must not inherit stale tokens.
        String appUrl = createAppWithBillingService("user", "appdel-app");
        String scope = appUrl + "/external_services/billing-api";

        Response appSignIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "API_KEY",
                    "api_key": "shared"
                }
                """.formatted(scope), "authorization", "user");
        verify(appSignIn, 200, "true");

        Response del = send(HttpMethod.DELETE, "/v1/" + appUrl, null, "", "authorization", "user");
        assertEquals(200, del.status(), () -> del.body());

        // Re-create the app (same url + service); the previously stored APP-level credential must be gone.
        createAppWithBillingService("user", "appdel-app");

        ApiKeyData appKey = newAppKey(appUrl, "user");
        apiKeyStore.assignPerRequestApiKey(appKey);
        Response retrieve = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + scope + "\"}", "api-key", appKey.getPerRequestKey());
        assertEquals(404, retrieve.status(), "APP-level credentials must be purged when the application is deleted");
    }

    private String createAppWithBillingService(String role, String appName) throws Exception {
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", role);
        assertEquals(200, bucketResp.status());
        String bucket = ProxyUtil.MAPPER.readTree(bucketResp.body()).get("bucket").asText();
        String appUrl = "applications/" + bucket + "/" + appName;
        Response createApp = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "App With Billing",
                    "external_services": {
                        "billing-api": {
                            "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-API-Key" }
                        }
                    }
                }
                """, "authorization", role);
        assertEquals(200, createApp.status(), () -> createApp.body());
        return appUrl;
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDeleteUnknownExternalServiceReturns404() throws Exception {
        String appUrl = createPlainDynamicApp("user", "mgmt-del-missing-app");
        Response del = send(HttpMethod.DELETE, "/v1/" + appUrl + "/external-services/nope",
                null, "", "authorization", "user");
        assertEquals(404, del.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testManageDynamicExternalServiceForbiddenForNonOwner() throws Exception {
        // admin creates the app in admin's bucket; "user" is neither admin nor owner.
        String appUrl = createPlainDynamicApp("admin", "mgmt-owned-app");
        Response put = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/x", null, """
                {
                    "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-API-Key" }
                }
                """, "authorization", "user");
        assertEquals(403, put.status());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testAuthStatusReflectsUserSignInOnDynamicApp() throws Exception {
        String appUrl = createPlainDynamicApp("user", "mgmt-status-app");
        send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/billing-api", null, """
                {
                    "auth_settings": { "authentication_type": "API_KEY", "api_key_header": "X-API-Key" }
                }
                """, "authorization", "user");

        Response before = send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/billing-api",
                null, "", "authorization", "user");
        assertEquals("SIGNED_OUT",
                ProxyUtil.MAPPER.readTree(before.body()).get("auth_settings").get("user_level_auth_status").asText());

        send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s/external_services/billing-api",
                    "credentials_level": "USER",
                    "authentication_type": "API_KEY",
                    "api_key": "k"
                }
                """.formatted(appUrl), "authorization", "user");

        Response after = send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/billing-api",
                null, "", "authorization", "user");
        assertEquals("SIGNED_IN",
                ProxyUtil.MAPPER.readTree(after.body()).get("auth_settings").get("user_level_auth_status").asText());
    }

    private String createPlainDynamicApp(String role, String appName) throws Exception {
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "authorization", role);
        assertEquals(200, bucketResp.status());
        String bucket = ProxyUtil.MAPPER.readTree(bucketResp.body()).get("bucket").asText();
        String appUrl = "applications/" + bucket + "/" + appName;
        Response createApp = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {
                    "endpoint": "http://localhost:7001/v1/x",
                    "display_name": "Mgmt App"
                }
                """, "authorization", role);
        assertEquals(200, createApp.status(), () -> createApp.body());
        return appUrl;
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testExternalServiceIdWithSpecialCharsRejected() throws Exception {
        String appUrl = createPlainDynamicApp("user", "space-name-app");

        // Ids flow into URI-parsed scopes/paths, so a space (or any non [A-Za-z0-9-_]) is rejected.
        Response mgmt = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/my%20service", null, """
                {"auth_settings":{"authentication_type":"API_KEY","api_key_header":"X-Key"}}
                """, "authorization", "user");
        assertEquals(400, mgmt.status(), () -> mgmt.body());

        // Same rejection on the application PUT path (spaced external_services key).
        Response appPut = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {"endpoint":"http://x","display_name":"App","external_services":{
                    "my service":{"auth_settings":{"authentication_type":"API_KEY","api_key_header":"X-Key"}}}}
                """, "authorization", "user");
        assertEquals(400, appPut.status(), () -> appPut.body());

        // A valid id works; display_name may still carry spaces/special chars (it never hits a path/scope).
        Response ok = send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/billing-api", null, """
                {"display_name":"My Billing API (prod)","auth_settings":{"authentication_type":"API_KEY","api_key_header":"X-Key"}}
                """, "authorization", "user");
        assertEquals(200, ok.status(), () -> ok.body());
        Response get = send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/billing-api", null, "", "authorization", "user");
        assertEquals("My Billing API (prod)", ProxyUtil.MAPPER.readTree(get.body()).get("display_name").asText());

        // Read endpoints must return a clean 400 (not 500) for a malformed scope url (raw space).
        String badScope = appUrl + "/external_services/my service";
        Response badSignin = send(HttpMethod.POST, "/v1/ops/external-service/signin", null,
                "{\"url\":\"" + badScope + "\",\"credentials_level\":\"USER\",\"authentication_type\":\"API_KEY\",\"api_key\":\"k\"}",
                "authorization", "user");
        assertEquals(400, badSignin.status(), () -> badSignin.body());

        ApiKeyData badKey = newAppKey(appUrl, "user");
        apiKeyStore.assignPerRequestApiKey(badKey);
        Response badCred = send(HttpMethod.POST, "/v1/ops/external-service/credentials", null,
                "{\"url\":\"" + badScope + "\"}", "api-key", badKey.getPerRequestKey());
        assertEquals(400, badCred.status(), () -> badCred.body());
    }

    // ---------------------------------------------------------------------------------------------
    // Admin consent for DIAL-native services (§4)
    // ---------------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testAdminCanGrantAndWithdrawConsent() {
        Response grant = send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "admin");
        assertEquals(200, grant.status(), grant.body());

        Response withdraw = send(HttpMethod.DELETE, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "admin");
        assertEquals(200, withdraw.status(), withdraw.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testNonAdminCannotGrantConsent() {
        Response grant = send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "user");
        assertEquals(403, grant.status(), grant.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testNonAdminCannotWithdrawConsent() {
        send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "admin");

        Response withdraw = send(HttpMethod.DELETE, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "user");
        assertEquals(403, withdraw.status(), withdraw.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testConsentRejectedForNonDialNativeService() {
        Response grant = send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/salesforce/consent",
                null, "", "authorization", "admin");
        assertEquals(400, grant.status(), grant.body());
        assertTrue(grant.body().contains("DIAL_NATIVE"), grant.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testConsentForDynamicAppWithSpaceInPath() {
        // The route hands the controller a decoded app id, so the scope must be re-encoded before it is parsed
        // again — a raw space fails URI parsing outright, and any re-encoding difference would make the grant
        // and the redemption address different storage keys.
        Application app = new Application();
        app.setEndpoint("http://localhost:7001/v1/x");
        app.setExternalServices(Map.of("dial", new ExternalService()
                .setAuthSettings(ResourceAuthSettings.builder()
                        .authenticationType(AuthenticationType.DIAL_NATIVE)
                        .build())));
        dial.getProxy().getApplicationService().putApplication(
                ResourceDescriptorFactory.fromPublicUrl("applications/public/my%20app"),
                EtagHeader.ANY, null, app, false, AdminManagedFieldsWriteMode.AUTHORITATIVE);

        String consent = "/v1/applications/public/my%20app/external-services/dial/consent";
        Response grant = send(HttpMethod.POST, consent, null, "", "authorization", "admin");
        assertEquals(200, grant.status(), grant.body());

        // Withdraw reports true only if it addressed the very record the grant wrote.
        Response withdraw = send(HttpMethod.DELETE, consent, null, "", "authorization", "admin");
        verify(withdraw, 200, "true");
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testConsentForUnknownServiceIsNotFound() {
        Response grant = send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/nope/consent",
                null, "", "authorization", "admin");
        assertEquals(404, grant.status(), grant.body());
    }

    // ---------------------------------------------------------------------------------------------
    // Status for DIAL-native services (§8 item 6a): app level means approved, user level means the
    // caller's platform-wide offline credentials — never a per-service sign-in, which does not exist.
    // ---------------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDialNativeAppLevelStatusFollowsConsent() {
        assertEquals("SIGNED_OUT", dialNativeStatus("app_level_auth_status", "admin"));

        send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "admin");
        assertEquals("SIGNED_IN", dialNativeStatus("app_level_auth_status", "admin"));

        send(HttpMethod.DELETE, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "admin");
        assertEquals("SIGNED_OUT", dialNativeStatus("app_level_auth_status", "admin"));
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testDialNativeUserLevelStatusFollowsOfflineCredentials() throws Exception {
        // The user-facing surface is the deployment GET: listing an app's services is admin/owner-only.
        assertEquals("SIGNED_OUT", dialNativeStatusOnApp("user"));

        IdentityProvider provider = Mockito.mock(IdentityProvider.class);
        Mockito.when(provider.getOfflineClient()).thenReturn(ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("dial-credentials-manager")
                .clientSecret("secret")
                .authorizationEndpoint("http://localhost:9876/authorize")
                .tokenEndpoint("http://localhost:9876/token")
                .redirectUri("http://localhost:3000/callback")
                .scopesSupported(List.of("openid", "offline_access"))
                .build());
        Mockito.when(provider.extractUserIdFromIdToken("id-token-for-user")).thenReturn("user");
        Mockito.when(provider.extractIssuerFromIdToken(Mockito.any())).thenReturn("http://idp/realms/dial");
        Mockito.when(validator.resolveProvider(Mockito.any())).thenReturn(provider);

        TestWebServer.Handler handler = request -> new MockResponse()
                .setBody("""
                        {
                            "access_token": "offline-access-token",
                            "refresh_token": "offline-refresh-token",
                            "id_token": "id-token-for-user",
                            "expires_in": 3600
                        }
                        """)
                .setHeader("Content-Type", "application/json");
        try (TestWebServer ignore = new TestWebServer(9876, handler)) {
            Response signIn = send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                    { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                    """, "authorization", "user");
            assertEquals(200, signIn.status(), signIn.body());
        }

        assertEquals("SIGNED_IN", dialNativeStatusOnApp("user"));
        // Another identity is unaffected — offline credentials live in the caller's own bucket.
        assertEquals("SIGNED_OUT", dialNativeStatusOnApp("admin"));

        Response signOut = send(HttpMethod.POST, "/v1/user/offline-credentials/signout",
                null, "", "authorization", "user");
        assertEquals(200, signOut.status(), signOut.body());
        assertEquals("SIGNED_OUT", dialNativeStatusOnApp("user"));
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testConsentDoesNotAffectOtherServicesStatus() {
        send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/dial/consent",
                null, "", "authorization", "admin");

        JsonNode salesforce = externalService("salesforce", "admin");
        assertEquals("SIGNED_OUT", salesforce.get("auth_settings").get("app_level_auth_status").asText());
        assertEquals("SIGNED_OUT", salesforce.get("auth_settings").get("user_level_auth_status").asText());
    }

    private String dialNativeStatus(String field, String user) {
        return externalService("dial", user).get("auth_settings").get(field).asText();
    }

    private String dialNativeStatusOnApp(String user) {
        Response app = send(HttpMethod.GET, "/openai/applications/app-with-services", null, "", "authorization", user);
        assertEquals(200, app.status(), app.body());
        return ProxyUtil.convertToObject(app.body(), JsonNode.class)
                .get("external_services").get("dial").get("auth_settings").get("user_level_auth_status").asText();
    }

    private JsonNode externalService(String serviceId, String user) {
        Response list = send(HttpMethod.GET, "/v1/applications/app-with-services/external-services",
                null, "", "authorization", user);
        assertEquals(200, list.status(), list.body());
        for (JsonNode service : ProxyUtil.convertToObject(list.body(), JsonNode.class)) {
            if (serviceId.equals(service.get("id").asText())) {
                return service;
            }
        }
        throw new AssertionError("service '" + serviceId + "' missing from " + list.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testConsentDecisionsAreAudited() {
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try {
            send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/dial/consent",
                    null, "", "authorization", "admin");
            send(HttpMethod.DELETE, "/v1/applications/app-with-services/external-services/dial/consent",
                    null, "", "authorization", "admin");
            // a refusal must be recorded too
            send(HttpMethod.POST, "/v1/applications/app-with-services/external-services/dial/consent",
                    null, "", "authorization", "user");

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=external_service_consent"))
                    .toList();
            assertEquals(3, events.size(), events::toString);
            assertTrue(events.get(0).contains("action=GRANT"), events::toString);
            assertTrue(events.get(0).contains("outcome=SUCCESS"), events::toString);
            assertTrue(events.get(0).contains("application_id=app-with-services"), events::toString);
            assertTrue(events.get(1).contains("action=WITHDRAW"), events::toString);
            assertTrue(events.get(2).contains("outcome=DENIED"), events::toString);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInRejectedForDialNativeService() {
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "USER",
                    "authentication_type": "DIAL_NATIVE"
                }
                """.formatted(DIAL_NATIVE_SCOPE), "authorization", "user");
        assertEquals(400, signIn.status());
        assertTrue(signIn.body().contains("not applicable"), signIn.body());
    }

    @Test
    @DialConfigLocation("dial-config/external-service-credentials.json")
    void testSignInRejectedForDialNativeServiceAtApplicationLevel() {
        Response signIn = send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {
                    "url": "%s",
                    "credentials_level": "APPLICATION",
                    "authentication_type": "DIAL_NATIVE"
                }
                """.formatted(DIAL_NATIVE_SCOPE), "authorization", "user");
        assertEquals(400, signIn.status());
    }

    private ApiKeyData newAppKey(String sourceDeployment, String role) {
        ApiKeyData perRequestKey = new ApiKeyData();
        perRequestKey.setExtractedClaims(createClaims(role));
        perRequestKey.setSourceDeployment(sourceDeployment);
        perRequestKey.setTraceId("trace-id");
        return perRequestKey;
    }
}
