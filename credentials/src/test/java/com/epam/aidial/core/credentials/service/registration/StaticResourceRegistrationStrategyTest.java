package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class StaticResourceRegistrationStrategyTest {

    @Mock
    private AuthorizationServerMetadataService authorizationServerMetadataService;

    @Mock
    private ProtectedResourceMetadataService protectedResourceMetadataService;

    @InjectMocks
    private StaticResourceRegistrationStrategy resourceRegistrationStrategy;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
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
        when(protectedResourceMetadata.getScopesSupported()).thenReturn(List.of("scope1"));

        when(protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint)).thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getAuthorizationEndpoint()).thenReturn("https://static.auth.endpoint");
        when(authorizationServerMetadata.getTokenEndpoint()).thenReturn("https://static.token.endpoint");
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256", "plain"));
        when(authorizationServerMetadata.getScopesSupported()).thenReturn(List.of("scope2", "scope3"));

        when(authorizationServerMetadataService.getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, false))
                .thenReturn(authorizationServerMetadata);

        // When
        ClientRegistration result = resourceRegistrationStrategy.register(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(result);
        assertEquals("staticClientId", result.getClientId());
        assertEquals("staticClientSecret", result.getClientSecret());
        assertEquals("https://static.redirect.uri", result.getRedirectUri());
        assertEquals("S256", result.getCodeChallengeMethod());
        List<String> actualScopesSupported = result.getScopesSupported();
        Collections.sort(actualScopesSupported);
        assertEquals(List.of("scope1", "scope2", "scope3"), actualScopesSupported);
        verify(authorizationServerMetadataService, times(1)).getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, false);
    }

    // TODO: add more tests
}
