package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationRequest;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationResponse;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DynamicResourceRegistrationStrategyTest {

    @Mock
    private AuthorizationServerMetadataService authorizationServerMetadataService;

    @Mock
    private ResourceAuthorizationClient resourceAuthorizationClient;

    @Mock
    private ProtectedResourceMetadataService protectedResourceMetadataService;

    private DynamicResourceRegistrationStrategy resourceRegistrationStrategy;

    @BeforeEach
    void setUp() {
        resourceRegistrationStrategy = new DynamicResourceRegistrationStrategy(
                authorizationServerMetadataService, resourceAuthorizationClient, protectedResourceMetadataService, List.of());
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
        when(protectedResourceMetadata.getScopesSupported()).thenReturn(List.of("scope1", "scope2"));

        when(protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint)).thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getRegistrationEndpoint()).thenReturn("https://auth.server/registration");
        when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256"));

        when(authorizationServerMetadataService.getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, true))
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
        List<String> actualScopesSupported = result.getScopesSupported();
        Collections.sort(actualScopesSupported);
        assertEquals(List.of("scope1", "scope2"), actualScopesSupported);
        verify(authorizationServerMetadataService, times(1)).getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, true);
        verify(resourceAuthorizationClient, times(1)).executePost(
                anyString(), any(ClientRegistrationRequest.class), anyString(), eq(ClientRegistrationResponse.class));
    }

    @Test
    void testRegister_propagatesTokenEndpointAuthMethodFromResponse() {
        ClientRegistration result = runRegistrationWithResponseMethod("client_secret_basic");

        assertEquals("client_secret_basic", result.getTokenEndpointAuthMethod());
    }

    @Test
    void testRegister_nullTokenEndpointAuthMethodInResponse_resolvesToDefault() {
        ClientRegistration result = runRegistrationWithResponseMethod(null);

        // A fresh DCR response that omits the method is persisted as the resolved default, so
        // GETs return the concrete method DIAL will use instead of an ambiguous null.
        assertEquals("client_secret_post", result.getTokenEndpointAuthMethod());
    }

    @Test
    void testRegister_unsupportedTokenEndpointAuthMethodInResponse_fails() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> runRegistrationWithResponseMethod("private_key_jwt"));

        assertTrue(error.getMessage().contains("private_key_jwt"),
                "Error must name the unsupported method, got: " + error.getMessage());
    }

    private ClientRegistration runRegistrationWithResponseMethod(String responseMethod) {
        String resourceId = "testResource";
        String resourceEndpoint = "https://test.endpoint.com";
        String resourceRedirectUri = "https://redirect.uri";

        ResourceAuthSettings resourceAuthSettings = mock(ResourceAuthSettings.class);
        when(resourceAuthSettings.getRedirectUri()).thenReturn(resourceRedirectUri);

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata = mock(AuthorizationServerProtectedResourceMetadata.class);
        when(protectedResourceMetadata.getScopesSupported()).thenReturn(List.of("scope1"));

        when(protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint))
                .thenReturn(protectedResourceMetadata);

        AuthorizationServerMetadata authorizationServerMetadata = mock(AuthorizationServerMetadata.class);
        when(authorizationServerMetadata.getRegistrationEndpoint()).thenReturn("https://auth.server/registration");
        // Not reached when validation rejects the response method before code-challenge resolution.
        lenient().when(authorizationServerMetadata.getCodeChallengeMethodsSupported()).thenReturn(List.of("S256"));

        when(authorizationServerMetadataService.getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, true))
                .thenReturn(authorizationServerMetadata);

        ClientRegistrationResponse clientRegistrationResponse = mock(ClientRegistrationResponse.class);
        // Builder-input getters are read after validation; the unsupported-method scenario throws
        // before they're touched, so mark them lenient to satisfy strict-stub checking.
        lenient().when(clientRegistrationResponse.getClientName()).thenReturn("testClientName");
        lenient().when(clientRegistrationResponse.getClientId()).thenReturn("testClientId");
        lenient().when(clientRegistrationResponse.getClientSecret()).thenReturn("testClientSecret");
        lenient().when(clientRegistrationResponse.getRedirectUris()).thenReturn(List.of(resourceRedirectUri));
        when(clientRegistrationResponse.getTokenEndpointAuthMethod()).thenReturn(responseMethod);

        when(resourceAuthorizationClient.executePost(
                eq("https://auth.server/registration"),
                any(ClientRegistrationRequest.class),
                eq(ContentType.APPLICATION_JSON.toString()),
                eq(ClientRegistrationResponse.class)))
                .thenReturn(clientRegistrationResponse);

        return resourceRegistrationStrategy.register(resourceId, resourceEndpoint, resourceAuthSettings);
    }
}
