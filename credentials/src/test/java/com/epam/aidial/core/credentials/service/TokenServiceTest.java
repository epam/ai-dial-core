package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenEndpointAuthMethod;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private ResourceAuthorizationClient resourceAuthorizationClient;

    @Test
    void testGetToken_usesRedirectUriFromGlobalAllowedList() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient,
                List.of("http://admin/callback", "http://chat/callback"));

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .redirectUri("http://chat/callback")
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), any(), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("redirect_uri=http%3A%2F%2Fchat%2Fcallback"),
                "Expected redirect_uri from sign-in request, got: " + formData);
    }

    @Test
    void testGetToken_usesRedirectUriMatchingToolsetOwnUri() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .redirectUri("http://admin/callback")
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), any(), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("redirect_uri=http%3A%2F%2Fadmin%2Fcallback"),
                "Expected toolset's own redirect_uri to be accepted, got: " + formData);
    }

    @Test
    void testGetToken_fallsBackToToolsetRedirectUri() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient,
                List.of("http://chat/callback"));

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), any(), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("redirect_uri=http%3A%2F%2Fadmin%2Fcallback"),
                "Should fall back to toolset's redirect_uri, got: " + formData);
    }

    @Test
    void testGetToken_fallsBackWhenRedirectUriIsBlank() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .redirectUri("  ")
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), any(), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("redirect_uri=http%3A%2F%2Fadmin%2Fcallback"),
                "Should fall back when redirect_uri is blank, got: " + formData);
    }

    @Test
    void testGetToken_rejectsRedirectUriNotInAllowedList() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient,
                List.of("http://chat/callback"));

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .redirectUri("http://evil/callback")
                .build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tokenService.getToken("resource-1", authSettings, signInRequest));

        assertEquals("Provided redirect_uri is not in the list of allowed redirect URIs", exception.getMessage());
        verifyNoInteractions(resourceAuthorizationClient);
    }

    @Test
    void testGetToken_nullAuthMethod_defaultsToBasicPerSpec() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                // tokenEndpointAuthMethod = null (legacy data or operator omitted the field)
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), headersCaptor.capture(), eq(TokenResponse.class));

        // RFC 6749 §2.3.1 MUST-support BASIC; OAuth 2.1 deprecates POST. Default is BASIC.
        String expected = "Basic " + Base64.getEncoder().encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headersCaptor.getValue().get("Authorization"));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("client_id="), "client_id must be in body: " + formData);
        assertFalse(formData.contains("client_secret="), "client_secret must not be in body: " + formData);
    }

    @Test
    void testGetToken_clientSecretBasic_sendsBasicHeaderWithClientIdInBody() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client id")
                .clientSecret("s3cret/with+special:chars")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .tokenEndpointAuthMethod(TokenEndpointAuthMethod.CLIENT_SECRET_BASIC.value())
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), headersCaptor.capture(), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        // client_id is sent in the body even under Basic (RFC 6749 §3.2.1 MAY) so servers that look up
        // the client by the body client_id (e.g. FastMCP's OAuth Proxy) work; the SECRET stays only in
        // the Basic header — no second authentication method per RFC 6749 §2.3.1.
        assertTrue(formData.contains("client_id="), "client_id must be in body: " + formData);
        assertFalse(formData.contains("client_secret="), "client_secret must NOT be in body for basic: " + formData);

        // Plain RFC 7617 Basic: client_id and client_secret are concatenated as-is and base64'd
        // without URL-encoding. This is what Snowflake and most real-world authorization servers
        // expect (see comment in TokenService#buildBasicAuthHeader).
        String expected = "Basic " + Base64.getEncoder().encodeToString(
                "client id:s3cret/with+special:chars".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headersCaptor.getValue().get("Authorization"));
    }

    @Test
    void testGetToken_none_sendsClientIdOnlyAndNoSecret() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("public-client")
                .clientSecret("should-be-ignored")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .tokenEndpointAuthMethod(TokenEndpointAuthMethod.NONE.value())
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder()
                .code("auth-code")
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), any(), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("client_id=public-client"));
        assertFalse(formData.contains("client_secret"), "client_secret must not be sent for public clients: " + formData);
    }

    @Test
    void testGetToken_unsupportedAuthMethod_throws() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .tokenEndpointAuthMethod("private_key_jwt")
                .build();

        ResourceSignInRequest signInRequest = ResourceSignInRequest.builder().code("auth-code").build();

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> tokenService.getToken("resource-1", authSettings, signInRequest));

        assertTrue(exception.getMessage().contains("private_key_jwt"));
        verifyNoInteractions(resourceAuthorizationClient);
    }

    @Test
    void testGetToken_refresh_clientSecretBasic_sendsAuthorizationHeader() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .tokenEndpointAuthMethod(TokenEndpointAuthMethod.CLIENT_SECRET_BASIC.value())
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("new-access", "new-refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, "old-refresh-token");

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), headersCaptor.capture(), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("grant_type=refresh_token"));
        assertTrue(formData.contains("refresh_token=old-refresh-token"));
        assertTrue(formData.contains("client_id="), "client_id must be in body for basic: " + formData);
        assertFalse(formData.contains("client_secret="), "client_secret must NOT be in body for basic: " + formData);

        String expected = "Basic " + Base64.getEncoder().encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headersCaptor.getValue().get("Authorization"));
    }

    @Test
    void testGetToken_refresh_nullAuthMethod_defaultsToBasicPerSpec() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        ResourceAuthSettings authSettings = ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                // null tokenEndpointAuthMethod (legacy data or operator omitted the field)
                .build();

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenReturn(new TokenResponse("new-access", "new-refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, "old-refresh-token");

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), headersCaptor.capture(), eq(TokenResponse.class));

        String expected = "Basic " + Base64.getEncoder().encodeToString("client-id:client-secret".getBytes(StandardCharsets.UTF_8));
        assertEquals(expected, headersCaptor.getValue().get("Authorization"));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("client_id="), "client_id must be in body: " + formData);
        assertFalse(formData.contains("client_secret="), "client_secret must not be in body: " + formData);
    }
}
