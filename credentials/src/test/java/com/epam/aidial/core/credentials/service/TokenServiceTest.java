package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), eq(TokenResponse.class));

        String formData = formDataCaptor.getValue();
        assertTrue(formData.contains("redirect_uri=http%3A%2F%2Fchat%2Fcallback"),
                "Expected redirect_uri from sign-in request, got: " + formData);
    }

    @Test
    void testGetToken_usesRedirectUriMatchingToolsetOwnUri() {
        // Global list is empty, but toolset's own redirect_uri is allowed
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

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), eq(TokenResponse.class));

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

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), eq(TokenResponse.class));

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

        when(resourceAuthorizationClient.executePost(any(), any(), any(), any()))
                .thenReturn(new TokenResponse("access", "refresh", 3600L));

        tokenService.getToken("resource-1", authSettings, signInRequest);

        ArgumentCaptor<String> formDataCaptor = ArgumentCaptor.forClass(String.class);
        verify(resourceAuthorizationClient).executePost(
                eq("http://auth-server/token"), formDataCaptor.capture(),
                eq("application/x-www-form-urlencoded"), eq(TokenResponse.class));

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
}
