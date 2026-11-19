package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dynamic client registration, metadata lookup and PKCE for external services (openspec change
 * add-external-service-dcr). The provider listens on 9876: the service's API at {@code /api}, its protected resource
 * metadata, an authorization server with a registration endpoint and a token endpoint. Services name it in
 * {@code auth_settings.discovery_url}.
 */
public class ExternalServiceDcrApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/external-service-dcr.json";
    private static final String API = "http://localhost:9876/api";
    private static final String TOKEN_RESPONSE = """
            {"access_token": "at-1", "refresh_token": "rt-1", "expires_in": 3600}
            """;

    @Test
    @DialConfigLocation(CONFIG)
    void testCreateRegistersClient() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-create");

            Response put = putService(appUrl, "crm", dynamicBody());
            assertEquals(200, put.status(), put::body);

            assertEquals(1, provider.count("POST /register"));
            JsonNode auth = getService(appUrl, "crm").get("auth_settings");
            assertEquals("dcr-client", auth.get("client_id").asText());
            assertEquals("http://localhost:9876/authorize", auth.get("authorization_endpoint").asText());
            assertEquals("http://localhost:9876/token", auth.get("token_endpoint").asText());
            assertEquals(API, auth.get("resource").asText());
            assertNull(auth.get("client_secret"));
            assertNull(auth.get("code_verifier"));
            assertEquals("S256", auth.get("code_challenge_method").asText());
            assertNotNull(auth.get("code_challenge"));

            String registration = provider.registrationBody.get();
            assertTrue(registration.contains("http://admin/callback") && registration.contains("http://chat/callback"), registration);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRegistrationThroughApplicationWrite() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = userApplicationUrl("user", "dcr-app-write");
            Response put = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                    {
                        "endpoint": "http://localhost:7001/v1/x",
                        "display_name": "App",
                        "external_services": {"crm": %s}
                    }
                    """.formatted(dynamicBody()), "authorization", "user");
            assertEquals(200, put.status(), put::body);

            assertEquals(1, provider.count("POST /register"));
            assertEquals("dcr-client", getService(appUrl, "crm").get("auth_settings").get("client_id").asText());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testAuthorizationServerWithoutRegistrationRejected() throws Exception {
        try (Provider provider = Provider.withoutRegistration()) {
            String appUrl = createPlainDynamicApp("user", "dcr-no-registration");

            Response put = putService(appUrl, "crm", dynamicBody());
            assertEquals(400, put.status(), put::body);
            assertTrue(put.body().contains("does not support dynamic client registration"), put::body);

            assertEquals(0, provider.count("POST /register"));
            assertEquals(404, serviceStatus(appUrl, "crm"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNeitherClientNorEndpointRejected() throws Exception {
        String appUrl = createPlainDynamicApp("user", "dcr-no-endpoint");

        Response put = putService(appUrl, "crm", """
                {"auth_settings": {"authentication_type": "OAUTH"}}
                """);

        assertEquals(400, put.status(), put::body);
        assertTrue(put.body().contains("requires discovery_url"), put::body);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDynamicBodyWithTokenEndpointRejectedWithoutRequests() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-token-endpoint");

            Response put = putService(appUrl, "crm", """
                    {"auth_settings": {"authentication_type": "OAUTH", "discovery_url": "%s", "token_endpoint": "http://localhost:9876/token"}}
                    """.formatted(API));

            assertEquals(400, put.status(), put::body);
            assertTrue(put.body().contains("Field 'TOKEN_ENDPOINT' is forbidden"), put::body);
            assertEquals(List.of(), provider.requests);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDynamicBodyWithUnlistedRedirectUriRejectedWithoutRequests() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-unlisted-redirect");

            Response put = putService(appUrl, "crm", dynamicBodyWithRedirect("https://attacker.example/cb"));

            assertEquals(400, put.status(), put::body);
            assertTrue(put.body().contains("is not in toolsets.security.allowedRedirectUris"), put::body);
            assertEquals(List.of(), provider.requests);
            assertEquals(404, serviceStatus(appUrl, "crm"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDynamicBodyWithListedRedirectUriRegisters() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-listed-redirect");

            Response put = putService(appUrl, "crm", dynamicBodyWithRedirect("http://admin/callback"));

            assertEquals(200, put.status(), put::body);
            assertEquals(1, provider.count("POST /register"));
            assertFalse(provider.registrationBody.get().contains("attacker"), provider.registrationBody::get);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSignInWithAllowedRedirectUri() throws Exception {
        try (Provider provider = Provider.confidential("client_secret_post")) {
            String appUrl = createPlainDynamicApp("user", "dcr-signin-redirect");
            assertEquals(200, putService(appUrl, "crm", dynamicBody()).status());

            verify(signIn(appUrl + "/external_services/crm", "http://admin/callback"), 200, "true");

            assertEquals("http://admin/callback", provider.tokenForm().get("redirect_uri"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSignInWithUnlistedRedirectUriRejected() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-signin-unlisted");
            assertEquals(200, putService(appUrl, "crm", dynamicBody()).status());

            Response signIn = signIn(appUrl + "/external_services/crm", "https://attacker.example/cb");

            assertEquals(400, signIn.status(), signIn::body);
            assertEquals(0, provider.count("POST /token"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBlankRedirectUriAndDiscoveryUrlCountAsAbsent() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-blank-fields");

            Response dynamic = putService(appUrl, "crm", dynamicBodyWithRedirect(""));
            assertEquals(200, dynamic.status(), dynamic::body);
            assertEquals(1, provider.count("POST /register"));

            Response staticCreate = putService(appUrl, "other", """
                    {"auth_settings": %s}
                    """.formatted(staticSettings(", \"discovery_url\": \"\"")));
            assertEquals(200, staticCreate.status(), staticCreate::body);
            assertNull(getService(appUrl, "other").get("auth_settings").get("discovery_url"));

            Response toApiKey = putService(appUrl, "crm", """
                    {"auth_settings": {"authentication_type": "API_KEY", "api_key_header": "X-Key", "discovery_url": ""}}
                    """);
            assertEquals(200, toApiKey.status(), toApiKey::body);
            assertNull(getService(appUrl, "crm").get("auth_settings").get("discovery_url"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSwitchToOauthNeedsClientSecret() throws Exception {
        String appUrl = createPlainDynamicApp("user", "switch-needs-secret");
        assertEquals(200, putService(appUrl, "crm", """
                {"auth_settings": {"authentication_type": "API_KEY", "api_key_header": "X-Key"}}
                """).status());

        Response put = putService(appUrl, "crm", """
                {"auth_settings": {"authentication_type": "OAUTH", "client_id": "public-client",
                 "authorization_endpoint": "http://localhost:9876/authorize", "token_endpoint": "http://localhost:9876/token"}}
                """);

        assertEquals(400, put.status(), put::body);
        assertTrue(put.body().contains("Field 'CLIENT_SECRET' is required"), put::body);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRelativeDiscoveryUrlRejected() throws Exception {
        String appUrl = createPlainDynamicApp("user", "dcr-relative");

        Response put = putService(appUrl, "crm", """
                {"auth_settings": {"authentication_type": "OAUTH", "discovery_url": "/api"}}
                """);

        assertEquals(400, put.status(), put::body);
        assertTrue(put.body().contains("discovery_url must be an absolute"), put::body);
        assertEquals(404, serviceStatus(appUrl, "crm"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDiscoveryUrlRejectedForApiKey() throws Exception {
        String appUrl = createPlainDynamicApp("user", "dcr-api-key");

        Response put = putService(appUrl, "crm", """
                {"auth_settings": {"authentication_type": "API_KEY", "api_key_header": "X-Key", "discovery_url": "%s"}}
                """.formatted(API));

        assertEquals(400, put.status(), put::body);
        assertTrue(put.body().contains("Field 'DISCOVERY_URL' is forbidden"), put::body);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUpdateOmittingDiscoveryUrlKeepsIt() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-keep-discovery");
            assertEquals(200, putService(appUrl, "crm", dynamicBody()).status());
            ObjectNode resent = resendable(getService(appUrl, "crm"));
            ((ObjectNode) resent.get("auth_settings")).remove("discovery_url");

            assertEquals(200, putService(appUrl, "crm", resent.toString()).status());

            assertEquals(API, getService(appUrl, "crm").get("auth_settings").get("discovery_url").asText());
            assertEquals(1, provider.count("POST /register"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRoundTripKeepsRegistrationAndOauthFields() throws Exception {
        try (Provider provider = Provider.confidential("client_secret_post")) {
            String appUrl = createPlainDynamicApp("user", "dcr-round-trip");
            assertEquals(200, putService(appUrl, "crm", dynamicBody()).status());
            JsonNode created = getService(appUrl, "crm");

            ObjectNode resent = resendable(created);
            resent.put("display_name", "Renamed");
            Response update = putService(appUrl, "crm", resent.toString());
            assertEquals(200, update.status(), update::body);

            assertEquals(1, provider.count("POST /register"), "an update must not register again");
            JsonNode before = created.get("auth_settings");
            JsonNode after = getService(appUrl, "crm").get("auth_settings");
            for (String field : List.of("client_id", "token_endpoint_auth_method", "resource", "code_challenge", "code_challenge_method")) {
                assertEquals(before.get(field), after.get(field), field);
            }
            assertEquals("client_secret_post", after.get("token_endpoint_auth_method").asText());

            // the kept client_secret and code_verifier still work at sign-in
            signIn(appUrl + "/external_services/crm");
            Map<String, String> token = provider.tokenForm();
            assertEquals("dcr-value-1", token.get("client_secret"));
            assertEquals(after.get("code_challenge").asText(), s256(token.get("code_verifier")));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBlankResourceClearsIt() throws Exception {
        try (Provider ignore = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-blank-resource");
            assertEquals(200, putService(appUrl, "crm", dynamicBody()).status());
            ObjectNode resent = resendable(getService(appUrl, "crm"));
            ((ObjectNode) resent.get("auth_settings")).put("resource", "");

            assertEquals(200, putService(appUrl, "crm", resent.toString()).status());

            assertNull(getService(appUrl, "crm").get("auth_settings").get("resource"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTypeChangeKeepsNoOauthField() throws Exception {
        try (Provider ignore = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "dcr-type-change");
            assertEquals(200, putService(appUrl, "crm", dynamicBody()).status());

            Response update = putService(appUrl, "crm", """
                    {"auth_settings": {"authentication_type": "API_KEY", "api_key_header": "X-Key"}}
                    """);
            assertEquals(200, update.status(), update::body);

            JsonNode auth = getService(appUrl, "crm").get("auth_settings");
            for (String field : List.of("client_id", "token_endpoint", "authorization_endpoint", "resource", "code_challenge",
                    "code_challenge_method", "token_endpoint_auth_method")) {
                assertNull(auth.get(field), field);
            }
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPublicClientVerifierEncryptedAtRestAndNeverReturned() throws Exception {
        try (Provider provider = Provider.publicClient()) {
            String appUrl = createPlainDynamicApp("user", "dcr-public");
            assertEquals(200, putService(appUrl, "crm", dynamicBody()).status());
            String scope = appUrl + "/external_services/crm";

            signIn(scope);
            Map<String, String> token = provider.tokenForm();
            String verifier = token.get("code_verifier");
            assertNotNull(verifier, "sign-in must send the decrypted code_verifier");
            assertNull(token.get("client_secret"));

            String blob = readStoredBlob(appUrl);
            String stored = ProxyUtil.MAPPER.readTree(blob).at("/external_services/crm/auth_settings/code_verifier").asText();
            assertFalse(stored.isEmpty(), blob);
            assertFalse(blob.contains(verifier), "the code_verifier must be encrypted at rest");

            Response app = send(HttpMethod.GET, "/v1/" + appUrl, null, "", "authorization", "user");
            Response service = send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/crm", null, "", "authorization", "user");
            Response deployment = send(HttpMethod.GET, "/openai/applications/" + appUrl,
                    null, "", "authorization", "user");
            for (Response read : List.of(app, service, deployment)) {
                assertEquals(200, read.status(), read::body);
                assertFalse(read.body().contains("code_verifier"), read::body);
                assertFalse(read.body().contains(verifier), read::body);
            }
            assertEquals(API, ProxyUtil.MAPPER.readTree(deployment.body()).at("/external_services/crm/auth_settings/discovery_url").asText(),
                    deployment::body);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStaticClientWithoutEndpointMakesNoRequest() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "static-offline");

            Response put = putService(appUrl, "crm", """
                    {"auth_settings": %s}
                    """.formatted(staticSettings("")));
            assertEquals(200, put.status(), put::body);

            assertEquals(List.of(), provider.requests);
            JsonNode auth = getService(appUrl, "crm").get("auth_settings");
            assertNull(auth.get("code_challenge"));
            assertNull(auth.get("scopes_supported"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStaticClientWithoutEndpointAsksForPkce() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "static-pkce");

            Response put = putService(appUrl, "crm", """
                    {"auth_settings": %s}
                    """.formatted(staticSettings(", \"code_challenge_method\": \"S256\"")));
            assertEquals(200, put.status(), put::body);
            assertEquals(List.of(), provider.requests);

            JsonNode auth = getService(appUrl, "crm").get("auth_settings");
            signIn(appUrl + "/external_services/crm");
            assertEquals(auth.get("code_challenge").asText(), s256(provider.tokenForm().get("code_verifier")));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testAdminPanelStaticBodyWithBlankFieldsAccepted() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "static-admin-panel");
            String body = """
                    {"display_name": "CRM", "auth_settings": {
                        "authentication_type": "OAUTH",
                        "client_id": "static-client",
                        "client_secret": "static-value-1",
                        "authorization_endpoint": "http://localhost:9876/authorize",
                        "token_endpoint": "http://localhost:9876/token",
                        "token_endpoint_auth_method": "client_secret_basic",
                        "redirect_uri": "http://admin/external-service-signin",
                        "resource": "",
                        "scopes_supported": [],
                        "code_challenge_method": ""
                    }}
                    """;

            Response create = putService(appUrl, "crm", body);
            assertEquals(200, create.status(), create::body);
            Response update = putService(appUrl, "crm", body.replace("\"client_secret\": \"static-value-1\",", ""));
            assertEquals(200, update.status(), update::body);

            assertEquals(List.of(), provider.requests);
            JsonNode auth = getService(appUrl, "crm").get("auth_settings");
            assertNull(auth.get("code_challenge_method"));
            assertNull(auth.get("code_challenge"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStaticClientWithEndpointReadsMetadata() throws Exception {
        try (Provider provider = Provider.confidential()) {
            String appUrl = createPlainDynamicApp("user", "static-endpoint");

            Response put = putService(appUrl, "crm", """
                    {"auth_settings": %s}
                    """.formatted(staticSettings(", \"discovery_url\": \"" + API + "\"")));
            assertEquals(200, put.status(), put::body);

            assertEquals(0, provider.count("POST /register"));
            JsonNode service = getService(appUrl, "crm");
            JsonNode auth = service.get("auth_settings");
            assertEquals(API, auth.get("discovery_url").asText());
            assertEquals("static-client", auth.get("client_id").asText());
            assertEquals(API, auth.get("resource").asText());
            assertEquals("crm.read", auth.get("scopes_supported").get(0).asText());
            assertEquals("S256", auth.get("code_challenge_method").asText());
            assertNotNull(auth.get("code_challenge"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStaticClientWithFailingMetadataStoredAsSent() throws Exception {
        try (Provider provider = Provider.confidential()) {
            provider.server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/api", 500, "boom");
            provider.server.map(HttpMethod.GET, "/.well-known/oauth-authorization-server", 500, "boom");
            String appUrl = createPlainDynamicApp("user", "static-metadata-down");

            Response put = putService(appUrl, "crm", """
                    {"auth_settings": %s}
                    """.formatted(staticSettings(", \"discovery_url\": \"" + API + "\"")));
            assertEquals(200, put.status(), put::body);

            JsonNode auth = getService(appUrl, "crm").get("auth_settings");
            assertEquals("static-client", auth.get("client_id").asText());
            assertNull(auth.get("code_challenge"));
            assertNull(auth.get("resource"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUnreachableDiscoveryUrlRejected() throws Exception {
        String unreachable = "http://localhost:1/api";
        String appUrl = createPlainDynamicApp("user", "dcr-unreachable");

        Response dynamic = putService(appUrl, "crm", """
                {"auth_settings": {"authentication_type": "OAUTH", "discovery_url": "%s"}}
                """.formatted(unreachable));
        assertEquals(400, dynamic.status(), dynamic::body);
        assertTrue(dynamic.body().startsWith("External service 'crm': "), dynamic::body);

        Response staticClient = putService(appUrl, "crm", """
                {"auth_settings": %s}
                """.formatted(staticSettings(", \"discovery_url\": \"" + unreachable + "\"")));
        assertEquals(400, staticClient.status(), staticClient::body);
        assertEquals(404, serviceStatus(appUrl, "crm"));

        Response appWrite = send(HttpMethod.PUT, "/v1/" + appUrl, null, """
                {"endpoint": "http://localhost:7001/v1/x", "display_name": "App",
                 "external_services": {"crm": {"auth_settings": {"authentication_type": "OAUTH", "discovery_url": "%s"}}}}
                """.formatted(unreachable), "authorization", "user");
        assertEquals(400, appWrite.status(), appWrite::body);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testStaticClientWithInvalidMetadataRejected() throws Exception {
        try (Provider provider = Provider.confidential()) {
            provider.server.map(HttpMethod.GET, "/.well-known/oauth-protected-resource/api", 200,
                    "{\"resource\": \"https://other.example.com\", \"authorization_servers\": [\"http://localhost:9876\"]}",
                    "Content-Type", "application/json");
            String appUrl = createPlainDynamicApp("user", "static-metadata-invalid");

            Response put = putService(appUrl, "crm", """
                    {"auth_settings": %s}
                    """.formatted(staticSettings(", \"discovery_url\": \"" + API + "\"")));

            assertEquals(400, put.status(), put::body);
            assertTrue(put.body().contains("Protected resource metadata is not the same"), put::body);
            assertEquals(404, serviceStatus(appUrl, "crm"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUserAuthoredServiceRegistersAndRoundTrips() throws Exception {
        try (Provider provider = Provider.confidential("client_secret_post")) {
            String path = "/v1/applications/user-services-app/external-services/crm";
            Response put = send(HttpMethod.PUT, path, null, dynamicBody(), "authorization", "user");
            assertEquals(200, put.status(), put::body);
            assertEquals(1, provider.count("POST /register"));

            Response get = send(HttpMethod.GET, path, null, "", "authorization", "user");
            assertEquals(200, get.status(), get::body);
            ObjectNode resent = (ObjectNode) ProxyUtil.MAPPER.readTree(get.body());
            assertEquals("dcr-client", resent.get("auth_settings").get("client_id").asText());
            resent.remove("id");
            resent.put("display_name", "Renamed");
            assertEquals(200, send(HttpMethod.PUT, path, null, resent.toString(), "authorization", "user").status());

            assertEquals(1, provider.count("POST /register"));
            signIn("applications/user-services-app/external_services/crm");
            Map<String, String> token = provider.tokenForm();
            assertEquals("dcr-value-1", token.get("client_secret"));
            assertEquals(resent.get("auth_settings").get("code_challenge").asText(), s256(token.get("code_verifier")));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testConfigFileServiceWithoutClientDropped() throws Exception {
        try (Provider provider = Provider.confidential()) {
            Response list = send(HttpMethod.GET, "/v1/applications/config-dcr-app/external-services", null, "", "authorization", "admin");
            assertEquals(200, list.status(), list::body);

            List<String> ids = Stream.of(ProxyUtil.MAPPER.readTree(list.body()))
                    .flatMap(node -> StreamSupport.stream(node.spliterator(), false))
                    .map(node -> node.get("id").asText())
                    .toList();
            assertEquals(List.of("billing-api"), ids);
            assertEquals(List.of(), provider.requests);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testAdminValidateDoesNotRegister() throws Exception {
        try (Provider provider = Provider.confidential()) {
            Response validate = send(HttpMethod.POST, "/v1/admin/validate", null, """
                    {
                      "manifests": [
                        {
                          "kind": "Application",
                          "name": "applications/platform/dcr-validated",
                          "spec": {
                            "endpoint": "http://localhost:7001/v1/x",
                            "display_name": "Validated",
                            "external_services": {"crm": %s}
                          }
                        }
                      ]
                    }
                    """.formatted(dynamicBody()), "authorization", "admin");
            assertEquals(200, validate.status(), validate::body);
            assertEquals(0, ProxyUtil.MAPPER.readTree(validate.body()).path("failed").asInt(), validate::body);

            assertEquals(List.of(), provider.requests);
        }
    }

    private static String dynamicBody() {
        return """
                {"display_name": "CRM", "auth_settings": {"authentication_type": "OAUTH", "discovery_url": "%s"}}
                """.formatted(API);
    }

    private static String dynamicBodyWithRedirect(String redirectUri) {
        return """
                {"auth_settings": {"authentication_type": "OAUTH", "discovery_url": "%s", "redirect_uri": "%s"}}
                """.formatted(API, redirectUri);
    }

    private static String staticSettings(String extra) {
        return """
                {
                    "authentication_type": "OAUTH",
                    "client_id": "static-client",
                    "client_secret": "static-value-1",
                    "authorization_endpoint": "http://localhost:9876/authorize",
                    "token_endpoint": "http://localhost:9876/token"%s
                }
                """.formatted(extra);
    }

    private Response putService(String appUrl, String serviceId, String body) {
        return send(HttpMethod.PUT, "/v1/" + appUrl + "/external-services/" + serviceId, null, body, "authorization", "user");
    }

    private int serviceStatus(String appUrl, String serviceId) {
        return send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/" + serviceId, null, "", "authorization", "user").status();
    }

    /** A read of the service as a write body: everything but the id. */
    private static ObjectNode resendable(JsonNode service) {
        ObjectNode body = service.deepCopy();
        body.remove("id");
        return body;
    }

    private JsonNode getService(String appUrl, String serviceId) throws Exception {
        Response get = send(HttpMethod.GET, "/v1/" + appUrl + "/external-services/" + serviceId, null, "", "authorization", "user");
        assertEquals(200, get.status(), get::body);
        return ProxyUtil.MAPPER.readTree(get.body());
    }

    private Response signIn(String scope, String redirectUri) {
        return send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {"url": "%s", "credentials_level": "USER", "authentication_type": "OAUTH", "code": "auth-code", "redirect_uri": "%s"}
                """.formatted(scope, redirectUri), "authorization", "user");
    }

    private void signIn(String scope) {
        verify(send(HttpMethod.POST, "/v1/ops/external-service/signin", null, """
                {"url": "%s", "credentials_level": "USER", "authentication_type": "OAUTH", "code": "auth-code"}
                """.formatted(scope), "authorization", "user"), 200, "true");
    }

    /** The service and its OAuth provider on one port; every request is recorded as "METHOD path". */
    private static final class Provider implements AutoCloseable {

        final TestWebServer server;
        final List<String> requests = new CopyOnWriteArrayList<>();
        final AtomicReference<String> registrationBody = new AtomicReference<>();
        final AtomicReference<String> tokenBody = new AtomicReference<>();

        static Provider confidential() {
            return new Provider(true, true, null);
        }

        static Provider confidential(String tokenEndpointAuthMethod) {
            return new Provider(true, true, tokenEndpointAuthMethod);
        }

        static Provider publicClient() {
            return new Provider(false, true, null);
        }

        static Provider withoutRegistration() {
            return new Provider(true, false, null);
        }

        private Provider(boolean issueSecret, boolean offerRegistration, String tokenEndpointAuthMethod) {
            server = new TestWebServer(9876, request -> {
                requests.add(request.getMethod() + " " + request.getPath());
                return new MockResponse().setResponseCode(404);
            });
            json(HttpMethod.GET, "/.well-known/oauth-protected-resource/api", """
                    {"resource": "http://localhost:9876/api", "authorization_servers": ["http://localhost:9876"],
                     "scopes_supported": ["crm.read"]}
                    """);
            json(HttpMethod.GET, "/.well-known/oauth-authorization-server", """
                    {
                      "issuer": "http://localhost:9876",
                      "authorization_endpoint": "http://localhost:9876/authorize",
                      "token_endpoint": "http://localhost:9876/token",
                      "code_challenge_methods_supported": ["S256"]
                      %s
                    }
                    """.formatted(offerRegistration ? ", \"registration_endpoint\": \"http://localhost:9876/register\"" : ""));
            server.map(HttpMethod.POST, "/register", request -> {
                requests.add("POST /register");
                registrationBody.set(request.getBody().readUtf8());
                return jsonResponse("""
                        {"client_id": "dcr-client", %s "redirect_uris": ["http://chat/callback"] %s}
                        """.formatted(issueSecret ? "\"client_secret\": \"dcr-value-1\"," : "",
                        tokenEndpointAuthMethod == null ? "" : ", \"token_endpoint_auth_method\": \"" + tokenEndpointAuthMethod + "\""));
            });
            server.map(HttpMethod.POST, "/token", request -> {
                requests.add("POST /token");
                tokenBody.set(request.getBody().readUtf8());
                return jsonResponse(TOKEN_RESPONSE);
            });
        }

        private void json(HttpMethod method, String path, String body) {
            server.map(method, path, request -> {
                requests.add(method.name() + " " + path);
                return jsonResponse(body);
            });
        }

        private static MockResponse jsonResponse(String body) {
            return new MockResponse().setBody(body).setHeader("Content-Type", "application/json");
        }

        long count(String request) {
            return requests.stream().filter(request::equals).count();
        }

        Map<String, String> tokenForm() {
            assertNotNull(tokenBody.get(), "the token endpoint was not called");
            return parseForm(tokenBody.get());
        }

        @Override
        public void close() {
            server.close();
        }
    }
}
