package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationRequest;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationResponse;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DynamicResourceRegistrationStrategyTest {

    @Mock
    private AuthorizationServerMetadataService authorizationServerMetadataService;

    @Mock
    private ResourceAuthorizationClient resourceAuthorizationClient;

    private DynamicResourceRegistrationStrategy resourceRegistrationStrategy;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        resourceRegistrationStrategy = new DynamicResourceRegistrationStrategy(
                authorizationServerMetadataService,
                resourceAuthorizationClient);
    }

    @Test
    void testCreateDynamicResourceRegistration_Success() {
        // Given
        String resourceId = "testResource";
        String resourceEndpoint = "https://test.endpoint.com";
        String resourceRedirectUri = "https://redirect.uri";

        ResourceAuthSettings resourceAuthSettings = mock(ResourceAuthSettings.class);
        when(resourceAuthSettings.getRedirectUri()).thenReturn(resourceRedirectUri);

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata = mock(AuthorizationServerProtectedResourceMetadata.class);
        when(protectedResourceMetadata.getAuthorizationServers()).thenReturn(List.of("https://auth.server"));

        when(resourceAuthorizationClient.executeGet(anyString(), eq(AuthorizationServerProtectedResourceMetadata.class)))
                .thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getRegistrationEndpoint()).thenReturn("https://auth.server/registration");
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256"));

        when(authorizationServerMetadataService.getAuthorizationServerMetadata(resourceId, resourceEndpoint, true))
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
        ClientRegistration result = resourceRegistrationStrategy.register(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(result);
        assertEquals("testClientId", result.getClientId());
        assertEquals("testClientSecret", result.getClientSecret());
        assertEquals(resourceRedirectUri, result.getRedirectUri());
        verify(authorizationServerMetadataService, times(1)).getAuthorizationServerMetadata(resourceId, resourceEndpoint, true);
        verify(resourceAuthorizationClient, times(1)).executePost(anyString(), any(ClientRegistrationRequest.class), anyString(), eq(ClientRegistrationResponse.class));
    }

    // TODO: add more tests
}
