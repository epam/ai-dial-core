package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

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

    private StaticResourceRegistrationStrategy resourceRegistrationStrategy;

    @BeforeEach
    void setup() {
        MockitoAnnotations.openMocks(this);
        resourceRegistrationStrategy = new StaticResourceRegistrationStrategy(authorizationServerMetadataService);
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

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getAuthorizationEndpoint()).thenReturn("https://static.auth.endpoint");
        when(authorizationServerMetadata.getTokenEndpoint()).thenReturn("https://static.token.endpoint");
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256", "plain"));

        when(authorizationServerMetadataService.getAuthorizationServerMetadata(resourceId, resourceEndpoint, false))
                .thenReturn(authorizationServerMetadata);

        // When
        ClientRegistration result = resourceRegistrationStrategy.register(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(result);
        assertEquals("staticClientId", result.getClientId());
        assertEquals("staticClientSecret", result.getClientSecret());
        assertEquals("https://static.redirect.uri", result.getRedirectUri());
        assertEquals("S256", result.getCodeChallengeMethod());
        verify(authorizationServerMetadataService, times(1)).getAuthorizationServerMetadata(resourceId, resourceEndpoint, false);
    }

    // TODO: add more tests
}
