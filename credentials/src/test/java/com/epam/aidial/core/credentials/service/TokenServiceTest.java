package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.ResourceSignInRequest;
import com.epam.aidial.core.credentials.data.credentials.TokenResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TokenServiceTest {

    @Mock
    private ResourceAuthorizationClient resourceAuthorizationClient;

    @InjectMocks
    private TokenService tokenService;

    @Test
    void testGetToken_usesRedirectUriFromSignInRequest() {
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
    void testGetToken_fallsBackToAuthSettingsRedirectUri() {
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
                "Should fall back to redirect_uri from auth settings, got: " + formData);
    }

    @Test
    void testGetToken_fallsBackToAuthSettingsWhenRedirectUriIsBlank() {
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
                "Should fall back to auth settings when redirect_uri is blank, got: " + formData);
    }
}
