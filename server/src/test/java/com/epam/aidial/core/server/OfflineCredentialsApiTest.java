package com.epam.aidial.core.server;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.config.AuthenticationType;
import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.IdentityProvider;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class OfflineCredentialsApiTest extends ResourceBaseTest {

    private static final String TOKEN_RESPONSE = """
            {
                "access_token": "offline-access-token",
                "refresh_token": "offline-refresh-token",
                "id_token": "id-token-for-user",
                "expires_in": 3600
            }
            """;

    private IdentityProvider provider;

    @BeforeEach
    void stubProvider() {
        provider = Mockito.mock(IdentityProvider.class);
        Mockito.when(provider.getOfflineClient()).thenReturn(ResourceAuthSettings.builder()
                .authenticationType(AuthenticationType.OAUTH)
                .clientId("dial-credentials-manager")
                .clientSecret("secret")
                .authorizationEndpoint("http://localhost:9877/authorize")
                .tokenEndpoint("http://localhost:9877/token")
                .redirectUri("http://localhost:3000/callback")
                .scopesSupported(List.of("openid", "offline_access"))
                .build());
        Mockito.when(provider.extractIssuerFromIdToken(Mockito.any())).thenReturn("http://idp/realms/dial");
        Mockito.when(validator.resolveProvider(Mockito.any())).thenReturn(provider);
    }

    @Test
    void testStatusReportsNotConnectedWithConnectParameters() {
        Response resp = send(HttpMethod.GET, "/v1/user/offline-credentials", null, "", "authorization", "user");
        assertEquals(200, resp.status(), resp.body());
        assertTrue(resp.body().contains("\"connected\":false"), resp.body());
        assertTrue(resp.body().contains("dial-credentials-manager"), resp.body());
        assertTrue(resp.body().contains("offline_access"), resp.body());
        assertFalse(resp.body().contains("secret"), "client secret must never be published");
    }

    @Test
    void testSignInStoresCredentialsAndStatusFlipsToConnected() throws Exception {
        Mockito.when(provider.extractUserIdFromIdToken("id-token-for-user")).thenReturn("user");
        try (TestWebServer ignore = new TestWebServer(9877, request -> new MockResponse()
                .setBody(TOKEN_RESPONSE).setHeader("Content-Type", "application/json"))) {

            Response signIn = send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                    { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                    """, "authorization", "user");
            assertEquals(200, signIn.status(), signIn.body());

            Response status = send(HttpMethod.GET, "/v1/user/offline-credentials", null, "", "authorization", "user");
            assertTrue(status.body().contains("\"connected\":true"), status.body());
            assertFalse(status.body().contains("client_id"), "no connect block once connected: " + status.body());
        }
    }

    @Test
    void testSignInRejectedWhenIdTokenBelongsToAnotherUser() throws Exception {
        Mockito.when(provider.extractUserIdFromIdToken("id-token-for-user")).thenReturn("someone-else");
        try (TestWebServer ignore = new TestWebServer(9877, request -> new MockResponse()
                .setBody(TOKEN_RESPONSE).setHeader("Content-Type", "application/json"))) {

            Response signIn = send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                    { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                    """, "authorization", "user");
            assertEquals(403, signIn.status(), signIn.body());

            Response status = send(HttpMethod.GET, "/v1/user/offline-credentials", null, "", "authorization", "user");
            assertTrue(status.body().contains("\"connected\":false"), "nothing may be stored: " + status.body());
        }
    }

    @Test
    void testSignInFailsClosedWhenNoIdTokenReturned() throws Exception {
        String noIdToken = """
                { "access_token": "a", "refresh_token": "r", "expires_in": 3600 }
                """;
        try (TestWebServer ignore = new TestWebServer(9877, request -> new MockResponse()
                .setBody(noIdToken).setHeader("Content-Type", "application/json"))) {

            Response signIn = send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                    { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                    """, "authorization", "user");
            assertEquals(400, signIn.status(), signIn.body());
            assertTrue(signIn.body().contains("no ID token"), signIn.body());

            Response status = send(HttpMethod.GET, "/v1/user/offline-credentials", null, "", "authorization", "user");
            assertTrue(status.body().contains("\"connected\":false"), "nothing may be stored: " + status.body());
        }
    }

    @Test
    void testSignOutDeletesCredentials() throws Exception {
        Mockito.when(provider.extractUserIdFromIdToken("id-token-for-user")).thenReturn("user");
        try (TestWebServer ignore = new TestWebServer(9877, request -> new MockResponse()
                .setBody(TOKEN_RESPONSE).setHeader("Content-Type", "application/json"))) {
            send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                    { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                    """, "authorization", "user");
        }

        Response signOut = send(HttpMethod.POST, "/v1/user/offline-credentials/signout", null, "", "authorization", "user");
        assertEquals(200, signOut.status(), signOut.body());

        Response status = send(HttpMethod.GET, "/v1/user/offline-credentials", null, "", "authorization", "user");
        assertTrue(status.body().contains("\"connected\":false"), status.body());
    }

    @Test
    void testPerRequestKeyCannotManageOfflineCredentials() {
        ApiKeyData appKey = newAppKey("app", "user");
        apiKeyStore.assignPerRequestApiKey(appKey);

        Response status = send(HttpMethod.GET, "/v1/user/offline-credentials", null, "",
                "api-key", appKey.getPerRequestKey());
        assertEquals(401, status.status(), status.body());

        Response signIn = send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                """, "api-key", appKey.getPerRequestKey());
        assertEquals(401, signIn.status(), signIn.body());
    }


    @Test
    void testSignInAndSignOutAreAudited() throws Exception {
        Mockito.when(provider.extractUserIdFromIdToken("id-token-for-user")).thenReturn("user");
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try (TestWebServer ignore = new TestWebServer(9877, request -> new MockResponse()
                .setBody(TOKEN_RESPONSE).setHeader("Content-Type", "application/json"))) {
            send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                    { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                    """, "authorization", "user");
            send(HttpMethod.POST, "/v1/user/offline-credentials/signout", null, "", "authorization", "user");

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=offline_credentials"))
                    .toList();
            assertEquals(2, events.size(), events::toString);
            assertTrue(events.get(0).contains("action=SIGN_IN"), events::toString);
            assertTrue(events.get(0).contains("outcome=SUCCESS"), events::toString);
            assertTrue(events.get(0).contains("user_id=user"), events::toString);
            assertTrue(events.get(1).contains("action=SIGN_OUT"), events::toString);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
    }

    @Test
    void testRejectedSignInIsAudited() throws Exception {
        Mockito.when(provider.extractUserIdFromIdToken("id-token-for-user")).thenReturn("someone-else");
        Logger auditLogger = (Logger) LoggerFactory.getLogger("DIAL_OBO_AUDIT");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        auditLogger.addAppender(appender);
        Level previous = auditLogger.getLevel();
        auditLogger.setLevel(Level.INFO);
        try (TestWebServer ignore = new TestWebServer(9877, request -> new MockResponse()
                .setBody(TOKEN_RESPONSE).setHeader("Content-Type", "application/json"))) {
            send(HttpMethod.POST, "/v1/user/offline-credentials/signin", null, """
                    { "code": "auth-code", "redirect_uri": "http://localhost:3000/callback" }
                    """, "authorization", "user");

            List<String> events = appender.list.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("event=offline_credentials"))
                    .toList();
            assertEquals(1, events.size(), events::toString);
            assertTrue(events.get(0).contains("action=SIGN_IN"), events::toString);
            assertFalse(events.get(0).contains("outcome=SUCCESS"), events::toString);
        } finally {
            auditLogger.setLevel(previous);
            auditLogger.detachAppender(appender);
        }
    }

    private ApiKeyData newAppKey(String sourceDeployment, String role) {
        ApiKeyData perRequestKey = new ApiKeyData();
        perRequestKey.setExtractedClaims(createClaims(role));
        perRequestKey.setSourceDeployment(sourceDeployment);
        perRequestKey.setTraceId("trace-id");
        return perRequestKey;
    }
}
