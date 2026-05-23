package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StaticResourceRegistrationStrategyTest {

    @Mock
    private AuthorizationServerMetadataService authorizationServerMetadataService;

    @Mock
    private ProtectedResourceMetadataService protectedResourceMetadataService;

    @InjectMocks
    private StaticResourceRegistrationStrategy resourceRegistrationStrategy;

    @Test
    void testCreateStaticResourceRegistrationWithUserProvidedData() {
        // Given
        String resourceId = "staticResource";
        String resourceEndpoint = "https://test.endpoint.com";
        String clientId = "staticClientId";
        String clientSecret = "staticClientSecret";
        String redirectUri = "https://static.redirect.uri";
        String authorizationEndpoint = "https://static.auth.endpoint";
        String tokenEndpoint = "https://static.token.endpoint";
        String codeChallengeMethod = "S256";
        List<String> scopesSupported = List.of("scope1", "scope2");

        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .redirectUri(redirectUri)
                .authorizationEndpoint(authorizationEndpoint)
                .tokenEndpoint(tokenEndpoint)
                .codeChallengeMethod(codeChallengeMethod)
                .scopesSupported(scopesSupported)
                .build();

        // When
        ClientRegistration result = resourceRegistrationStrategy.register(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(result);
        assertEquals(clientId, result.getClientId());
        assertEquals(clientSecret, result.getClientSecret());
        assertEquals(redirectUri, result.getRedirectUri());
        assertEquals(authorizationEndpoint, result.getAuthorizationEndpoint());
        assertEquals(tokenEndpoint, result.getTokenEndpoint());
        assertEquals(codeChallengeMethod, result.getCodeChallengeMethod());
        assertEquals(scopesSupported, result.getScopesSupported());
        verifyNoInteractions(protectedResourceMetadataService);
        verifyNoInteractions(authorizationServerMetadataService);
    }

    @Test
    void testCreateStaticResourceRegistrationWithDiscoveredData() {
        // Given
        String resourceId = "staticResource";
        String resourceEndpoint = "https://test.endpoint.com";
        String clientId = "staticClientId";
        String clientSecret = "staticClientSecret";
        String redirectUri = "https://static.redirect.uri";

        String authorizationEndpoint = "https://static.auth.endpoint";
        String tokenEndpoint = "https://static.token.endpoint";
        String codeChallengeMethod = "S256";

        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .redirectUri(redirectUri)
                .build();

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata = mock(AuthorizationServerProtectedResourceMetadata.class);
        when(protectedResourceMetadata.getScopesSupported()).thenReturn(List.of("scope1"));

        when(protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint)).thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getAuthorizationEndpoint()).thenReturn(authorizationEndpoint);
        when(authorizationServerMetadata.getTokenEndpoint()).thenReturn(tokenEndpoint);
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of(codeChallengeMethod));

        when(authorizationServerMetadataService.getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, false))
                .thenReturn(authorizationServerMetadata);

        // When
        ClientRegistration result = resourceRegistrationStrategy.register(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(result);
        assertEquals(clientId, result.getClientId());
        assertEquals(clientSecret, result.getClientSecret());
        assertEquals(redirectUri, result.getRedirectUri());
        assertEquals(authorizationEndpoint, result.getAuthorizationEndpoint());
        assertEquals(tokenEndpoint, result.getTokenEndpoint());
        assertEquals(codeChallengeMethod, result.getCodeChallengeMethod());
        assertEquals(List.of("scope1"), result.getScopesSupported());
        verify(protectedResourceMetadataService).getProtectedResourceMetadata(
                resourceId, resourceEndpoint);
        verify(authorizationServerMetadataService).getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, false);
    }

    @Test
    void testStaticEndpointsNotOverwrittenByDiscovery() {
        // Given
        String resourceId = "staticResource";
        String resourceEndpoint = "https://test.endpoint.com";
        String clientId = "staticClientId";
        String clientSecret = "staticClientSecret";
        String redirectUri = "https://static.redirect.uri";

        // Static endpoints provided by the user
        String staticAuthorizationEndpoint = "https://static.auth.endpoint";
        String staticTokenEndpoint = "https://static.token.endpoint";
        List<String> scopesSupported = List.of("scope1", "scope2");

        // Discovery returns different endpoints and a codeChallengeMethod
        String discoveredAuthorizationEndpoint = "https://discovered.auth.endpoint";
        String discoveredTokenEndpoint = "https://discovered.token.endpoint";
        String discoveredCodeChallengeMethod = "S256";

        // codeChallengeMethod is null (simulates UI-created toolset), triggering discovery
        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder()
                .clientId(clientId)
                .clientSecret(clientSecret)
                .redirectUri(redirectUri)
                .authorizationEndpoint(staticAuthorizationEndpoint)
                .tokenEndpoint(staticTokenEndpoint)
                .scopesSupported(scopesSupported)
                .build();

        assertNull(resourceAuthSettings.getCodeChallengeMethod());

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata = mock(AuthorizationServerProtectedResourceMetadata.class);
        when(protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint))
                .thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getAuthorizationEndpoint()).thenReturn(discoveredAuthorizationEndpoint);
        when(authorizationServerMetadata.getTokenEndpoint()).thenReturn(discoveredTokenEndpoint);
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of(discoveredCodeChallengeMethod));

        when(authorizationServerMetadataService.getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, false))
                .thenReturn(authorizationServerMetadata);

        // When
        ClientRegistration result = resourceRegistrationStrategy.register(resourceId, resourceEndpoint, resourceAuthSettings);

        // Then
        assertNotNull(result);
        assertEquals(clientId, result.getClientId());
        assertEquals(clientSecret, result.getClientSecret());
        assertEquals(redirectUri, result.getRedirectUri());
        // Static endpoints must be preserved (not overwritten by discovery)
        assertEquals(staticAuthorizationEndpoint, result.getAuthorizationEndpoint());
        assertEquals(staticTokenEndpoint, result.getTokenEndpoint());
        // codeChallengeMethod should be filled from discovery
        assertEquals(discoveredCodeChallengeMethod, result.getCodeChallengeMethod());
        assertEquals(scopesSupported, result.getScopesSupported());
        // Verify discovery was called (because codeChallengeMethod was null)
        verify(protectedResourceMetadataService).getProtectedResourceMetadata(resourceId, resourceEndpoint);
        verify(authorizationServerMetadataService).getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, false);
    }

    @Test
    void testRegister_propagatesTokenEndpointAuthMethodFromSettings() {
        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder()
                .clientId("staticClientId")
                .clientSecret("staticClientSecret")
                .redirectUri("https://static.redirect.uri")
                .authorizationEndpoint("https://static.auth.endpoint")
                .tokenEndpoint("https://static.token.endpoint")
                .codeChallengeMethod("S256")
                .scopesSupported(List.of("scope1"))
                .tokenEndpointAuthMethod("client_secret_basic")
                .build();

        ClientRegistration result = resourceRegistrationStrategy.register(
                "staticResource", "https://test.endpoint.com", resourceAuthSettings);

        assertEquals("client_secret_basic", result.getTokenEndpointAuthMethod());
    }

    @Test
    void testRegister_nullTokenEndpointAuthMethodInSettings() {
        ResourceAuthSettings resourceAuthSettings = ResourceAuthSettings.builder()
                .clientId("staticClientId")
                .clientSecret("staticClientSecret")
                .redirectUri("https://static.redirect.uri")
                .authorizationEndpoint("https://static.auth.endpoint")
                .tokenEndpoint("https://static.token.endpoint")
                .codeChallengeMethod("S256")
                .scopesSupported(List.of("scope1"))
                .build();

        ClientRegistration result = resourceRegistrationStrategy.register(
                "staticResource", "https://test.endpoint.com", resourceAuthSettings);

        assertNull(result.getTokenEndpointAuthMethod());
    }
}
