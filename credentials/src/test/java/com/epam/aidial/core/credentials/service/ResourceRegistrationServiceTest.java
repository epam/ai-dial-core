package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationRequest;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationResponse;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class ResourceRegistrationServiceTest {

    @Mock
    private ResourceAuthorizationClient resourceAuthorizationClient;

    private ResourceRegistrationService resourceRegistrationService;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        resourceRegistrationService = new ResourceRegistrationService(resourceAuthorizationClient);
    }

    @Test
    void testCreateDynamicResourceRegistration_Success() {
        // Given
        String resourceId = "testResource";
        String resourceEndpoint = "https://test.endpoint.com";
        String resourceRedirectUri = "https://redirect.uri";

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata = mock(AuthorizationServerProtectedResourceMetadata.class);
        when(protectedResourceMetadata.getAuthorizationServers()).thenReturn(List.of("https://auth.server"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class)))
                .thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getRegistrationEndpoint()).thenReturn("https://auth.server/registration");
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenReturn(authorizationServerMetadata);

        ClientRegistrationResponse clientRegistrationResponse = mock(ClientRegistrationResponse.class);
        when(clientRegistrationResponse.getClientName()).thenReturn("testClientName");
        when(clientRegistrationResponse.getClientId()).thenReturn("testClientId");
        when(clientRegistrationResponse.getClientSecret()).thenReturn("testClientSecret");
        when(clientRegistrationResponse.getRedirectUris()).thenReturn(List.of(resourceRedirectUri));
        when(clientRegistrationResponse.getRedirectUris()).thenReturn(List.of(resourceRedirectUri));

        when(resourceAuthorizationClient.executePost(
            eq("https://auth.server/registration"),
            any(ClientRegistrationRequest.class),
            eq(ContentType.APPLICATION_JSON.toString()),
            eq(ClientRegistrationResponse.class)))
                .thenReturn(clientRegistrationResponse);

        // When
        ClientRegistration result = resourceRegistrationService.createDynamicResourceRegistration(resourceId, resourceEndpoint, resourceRedirectUri);

        // Then
        assertNotNull(result);
        assertEquals("testClientId", result.getClientId());
        assertEquals("testClientSecret", result.getClientSecret());
        assertEquals(resourceRedirectUri, result.getRedirectUri());
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerMetadata.class));
        verify(resourceAuthorizationClient, times(1)).executePost(anyString(), any(ClientRegistrationRequest.class), anyString(), eq(ClientRegistrationResponse.class));
    }

    @Test
    void testCreateStaticResourceRegistration_Success() {
        // Given
        String resourceId = "staticResource";
        String resourceEndpoint = "https://test.endpoint.com";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();
        resourceAuthSettings.setClientId("staticClientId");
        resourceAuthSettings.setClientSecret("staticClientSecret");
        resourceAuthSettings.setRedirectUri("https://static.redirect.uri");

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata = mock(AuthorizationServerProtectedResourceMetadata.class);
        when(protectedResourceMetadata.getAuthorizationServers()).thenReturn(List.of("https://auth.server"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class)))
                .thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getAuthorizationEndpoint()).thenReturn("https://static.auth.endpoint");
        when(authorizationServerMetadata.getTokenEndpoint()).thenReturn("https://static.token.endpoint");
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256", "plain"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenReturn(authorizationServerMetadata);

        // When
        ClientRegistration result = resourceRegistrationService.createStaticResourceRegistration(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(result);
        assertEquals("staticClientId", result.getClientId());
        assertEquals("staticClientSecret", result.getClientSecret());
        assertEquals("https://static.redirect.uri", result.getRedirectUri());
        assertEquals("plain", result.getCodeChallengeMethod());
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerMetadata.class));
    }

    @Test
    void testCreateDynamicResourceRegistration_HttpExceptionNotFound_ThrowsIllegalArgumentException() {
        // Given
        String resourceId = "testResource";
        String resourceEndpoint = "https://test.endpoint.com";

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class)))
                .thenThrow(new HttpException(HttpStatus.NOT_FOUND, "Server not found"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenThrow(new HttpException(HttpStatus.NOT_FOUND, "Server not found"));

        // When & Then
        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                resourceRegistrationService.createDynamicResourceRegistration(resourceId, resourceEndpoint, "https://redirect.uri"));

        assertTrue(exception.getMessage().contains("does not support OAuth authentication"));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerMetadata.class));
    }

    @Test
    void testCreateDynamicResourceRegistration_HttpExceptionNotFoundForForProtectedResource_ThrowsIllegalArgumentException() {
        // Given
        String resourceId = "testResource";
        String resourceEndpoint = "https://test.endpoint.com";
        String resourceRedirectUri = "https://redirect.uri";

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class)))
                .thenThrow(new HttpException(HttpStatus.NOT_FOUND, "Server not found"));

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getRegistrationEndpoint()).thenReturn("https://auth.server/registration");
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenReturn(authorizationServerMetadata);

        ClientRegistrationResponse clientRegistrationResponse = mock(ClientRegistrationResponse.class);
        when(clientRegistrationResponse.getClientName()).thenReturn("testClientName");
        when(clientRegistrationResponse.getClientId()).thenReturn("testClientId");
        when(clientRegistrationResponse.getClientSecret()).thenReturn("testClientSecret");
        when(clientRegistrationResponse.getRedirectUris()).thenReturn(List.of(resourceRedirectUri));
        when(clientRegistrationResponse.getRedirectUris()).thenReturn(List.of(resourceRedirectUri));

        when(resourceAuthorizationClient.executePost(
            eq("https://auth.server/registration"),
            any(ClientRegistrationRequest.class),
            eq(ContentType.APPLICATION_JSON.toString()),
            eq(ClientRegistrationResponse.class)))
                .thenReturn(clientRegistrationResponse);

        // When
        ClientRegistration result = resourceRegistrationService.createDynamicResourceRegistration(resourceId, resourceEndpoint, resourceRedirectUri);

        // Then
        assertNotNull(result);
        assertEquals("testClientId", result.getClientId());
        assertEquals("testClientSecret", result.getClientSecret());
        assertEquals(resourceRedirectUri, result.getRedirectUri());
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerMetadata.class));
        verify(resourceAuthorizationClient, times(1)).executePost(anyString(), any(ClientRegistrationRequest.class), anyString(), eq(ClientRegistrationResponse.class));
    }

    @Test
    void testCreateStaticResourceRegistration_HttpExceptionUnauthorized_ThrowsIllegalArgumentException() {
        // Given
        String resourceId = "staticResource";
        String resourceEndpoint = "https://test.endpoint.com";
        ResourceAuthSettings resourceAuthSettings = new ResourceAuthSettings();

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class)))
                .thenThrow(new HttpException(HttpStatus.UNAUTHORIZED, "Unauthorized"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenThrow(new HttpException(HttpStatus.UNAUTHORIZED, "Unauthorized"));

        // When & Then
        Exception exception = assertThrows(IllegalArgumentException.class, () ->
                resourceRegistrationService.createStaticResourceRegistration(resourceId, resourceEndpoint, resourceAuthSettings));

        assertTrue(exception.getMessage().contains("does not support OAuth authentication"));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerMetadata.class));
    }

    @Test
    void testCreateDynamicResourceRegistration_UnexpectedException_ThrowsHttpException() {
        // Given
        String resourceId = "testResource";
        String resourceEndpoint = "https://test.endpoint.com";

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class)))
                .thenThrow(new HttpException(500, "Authorization server returns error code"));

        // When & Then
        Exception exception = assertThrows(HttpException.class, () ->
                resourceRegistrationService.createDynamicResourceRegistration(resourceId, resourceEndpoint, "https://redirect.uri"));

        assertTrue(exception.getMessage().contains("Authorization server returns error code"));
        verify(resourceAuthorizationClient, times(1)).executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class));
        verifyNoMoreInteractions(resourceAuthorizationClient);
    }
}
