package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenEndpointAuthMethod;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
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
import static org.mockito.Mockito.times;
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
        assertFalse(formData.contains("client_id="), "client_id must not be in body: " + formData);
        assertFalse(formData.contains("client_secret="), "client_secret must not be in body: " + formData);
    }

    @Test
    void testGetToken_clientSecretBasic_sendsBasicHeaderWithoutBodyCredentials() {
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
        // Spec-pure Basic (RFC 6749 §2.3): credentials only in the Authorization header. Strict
        // servers (e.g. Notion MCP, issue #1703) reject a body client_id alongside the header as
        // "multiple authentication methods"; servers that need the body client_id (FastMCP's OAuth
        // Proxy, issue #1608) are handled by the retry — see the retry tests below.
        assertFalse(formData.contains("client_id="), "client_id must not be in body: " + formData);
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
        assertFalse(formData.contains("client_id="), "client_id must not be in body for basic: " + formData);
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
        assertFalse(formData.contains("client_id="), "client_id must not be in body: " + formData);
        assertFalse(formData.contains("client_secret="), "client_secret must not be in body: " + formData);
    }

    @Test
    void testGetToken_clientSecretBasic_retriesWithBodyClientIdOnInvalidClient() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenThrow(oauthError(HttpStatus.BAD_REQUEST, "invalid_client", "Missing client_id"))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        TokenResponse response = tokenService.getToken("resource-1", basicAuthSettings(),
                ResourceSignInRequest.builder().code("auth-code").build());

        assertEquals("access", response.getAccessToken());

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, String>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(resourceAuthorizationClient, times(2)).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), headersCaptor.capture(), eq(TokenResponse.class));

        List<String> attempts = formDataCaptor.getAllValues();
        assertFalse(attempts.get(0).contains("client_id="), "first attempt must be spec-pure Basic: " + attempts.get(0));
        assertTrue(attempts.get(1).contains("client_id=client-id"), "retry must carry client_id in body: " + attempts.get(1));
        assertFalse(attempts.get(1).contains("client_secret="), "secret must stay out of the body: " + attempts.get(1));
        for (Map<String, String> headers : headersCaptor.getAllValues()) {
            assertTrue(headers.get("Authorization").startsWith("Basic "), "Basic header expected on both attempts");
        }
    }

    @Test
    void testGetToken_clientSecretBasic_retriesOnBare401() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenThrow(new HttpException(HttpStatus.UNAUTHORIZED, "Authorization server returns 401 error code", Map.of(), null))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        TokenResponse response = tokenService.getToken("resource-1", basicAuthSettings(),
                ResourceSignInRequest.builder().code("auth-code").build());

        assertEquals("access", response.getAccessToken());
        verify(resourceAuthorizationClient, times(2)).executePost(any(), any(), any(), any(), any());
    }

    @Test
    void testGetToken_clientSecretBasic_retriesOnClientIdMismatchGrantError() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        // FastMCP's/Stack's OAuth proxy misreports a missing body client_id as invalid_grant (#1608)
        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenThrow(oauthError(HttpStatus.BAD_REQUEST, "invalid_grant",
                        "Client ID does not match the one used in the initial request."))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        TokenResponse response = tokenService.getToken("resource-1", basicAuthSettings(),
                ResourceSignInRequest.builder().code("auth-code").build());

        assertEquals("access", response.getAccessToken());
        verify(resourceAuthorizationClient, times(2)).executePost(any(), any(), any(), any(), any());
    }

    @Test
    void testGetToken_clientSecretBasic_retriesOnClientIdMentioningInvalidRequest() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenThrow(oauthError(HttpStatus.BAD_REQUEST, "invalid_request", "client_id is required"))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        TokenResponse response = tokenService.getToken("resource-1", basicAuthSettings(),
                ResourceSignInRequest.builder().code("auth-code").build());

        assertEquals("access", response.getAccessToken());
        verify(resourceAuthorizationClient, times(2)).executePost(any(), any(), any(), any(), any());
    }

    @Test
    void testGetToken_clientSecretBasic_doesNotRetryOnOrdinaryGrantError() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        HttpException expiredCode = oauthError(HttpStatus.BAD_REQUEST, "invalid_grant", "Authorization code expired");
        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any())).thenThrow(expiredCode);

        HttpException thrown = assertThrows(HttpException.class,
                () -> tokenService.getToken("resource-1", basicAuthSettings(),
                        ResourceSignInRequest.builder().code("auth-code").build()));

        assertEquals(expiredCode, thrown);
        verify(resourceAuthorizationClient, times(1)).executePost(any(), any(), any(), any(), any());
    }

    @Test
    void testGetToken_clientSecretBasic_doesNotRetryOnNonJsonErrorBody() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenThrow(new HttpException(HttpStatus.BAD_REQUEST, "Authorization server returns error code",
                        Map.of(), "<html>Bad Request</html>"));

        assertThrows(HttpException.class,
                () -> tokenService.getToken("resource-1", basicAuthSettings(),
                        ResourceSignInRequest.builder().code("auth-code").build()));

        verify(resourceAuthorizationClient, times(1)).executePost(any(), any(), any(), any(), any());
    }

    @Test
    void testGetToken_clientSecretBasic_propagatesFirstErrorWhenRetryFailsToo() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        HttpException firstError = oauthError(HttpStatus.UNAUTHORIZED, "invalid_client", "Client authentication failed");
        HttpException retryError = oauthError(HttpStatus.BAD_REQUEST, "invalid_request",
                "Client must not use multiple authentication methods");
        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenThrow(firstError)
                .thenThrow(retryError);

        HttpException thrown = assertThrows(HttpException.class,
                () -> tokenService.getToken("resource-1", basicAuthSettings(),
                        ResourceSignInRequest.builder().code("auth-code").build()));

        assertEquals(firstError, thrown);
        verify(resourceAuthorizationClient, times(2)).executePost(any(), any(), any(), any(), any());
    }

    @Test
    void testGetToken_refresh_clientSecretBasic_retriesWithBodyClientId() {
        TokenService tokenService = new TokenService(resourceAuthorizationClient, List.of());

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any(), any()))
                .thenThrow(oauthError(HttpStatus.BAD_REQUEST, "invalid_client", "Missing client_id"))
                .thenReturn(new TokenResponse("new-access", "new-refresh", 3600L));

        TokenResponse response = tokenService.getToken("resource-1", basicAuthSettings(), "old-refresh-token");

        assertEquals("new-access", response.getAccessToken());

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient, times(2)).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), any(), eq(TokenResponse.class));

        List<String> attempts = formDataCaptor.getAllValues();
        assertFalse(attempts.get(0).contains("client_id="), "first attempt must be spec-pure Basic: " + attempts.get(0));
        assertTrue(attempts.get(1).contains("client_id=client-id"), "retry must carry client_id in body: " + attempts.get(1));
        assertTrue(attempts.get(1).contains("refresh_token=old-refresh-token"));
    }

    private static ResourceAuthSettings basicAuthSettings() {
        return ResourceAuthSettings.builder()
                .clientId("client-id")
                .clientSecret("client-secret")
                .tokenEndpoint("http://auth-server/token")
                .redirectUri("http://admin/callback")
                .tokenEndpointAuthMethod(TokenEndpointAuthMethod.CLIENT_SECRET_BASIC.value())
                .build();
    }

    private static HttpException oauthError(HttpStatus status, String error, String description) {
        String body = "{\"error\":\"%s\",\"error_description\":\"%s\"}".formatted(error, description);
        return new HttpException(status, "Authorization server returns error code", Map.of(), body);
    }
}
