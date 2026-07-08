package com.epam.aidial.core.credentials.service.metadata;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.validation.AuthorizationServerMetadataValidator;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuthorizationServerMetadataServiceTest {

    private static final String RESOURCE_ID = "toolsets/foo/bar";
    private static final String BASE_URL = "https://keycloak.example.com";
    private static final String ISSUER = BASE_URL + "/realms/dial";
    // OpenID Connect Discovery 1.0 path-append form served by Keycloak.
    private static final String OIDC_APPEND_URL = ISSUER + "/.well-known/openid-configuration";

    private ResourceAuthorizationClient client;
    private AuthorizationServerMetadataService service;

    @BeforeEach
    void setUp() {
        client = mock(ResourceAuthorizationClient.class);
        service = new AuthorizationServerMetadataService(client, new AuthorizationServerMetadataValidator());
    }

    /**
     * Keycloak-style issuer: metadata resolves only at the OIDC path-append URL, every RFC 8414
     * path-insertion form 404s. The append form must be included in the fallback set for discovery to succeed.
     */
    @Test
    void discoversMetadataViaOidcPathAppendForm() {
        when(client.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenThrow(notFound());
        when(client.executeGet(eq(OIDC_APPEND_URL), eq(AuthorizationServerMetadata.class)))
                .thenReturn(metadata(ISSUER + "/clients-registrations/openid-connect"));

        AuthorizationServerMetadata result = service.getAuthorizationServerMetadata(
                RESOURCE_ID, BASE_URL + "/mcp", protectedResourceMetadata(), true);

        assertNotNull(result);
        assertEquals(ISSUER, result.getIssuer());
        assertEquals(ISSUER + "/clients-registrations/openid-connect", result.getRegistrationEndpoint());

        ArgumentCaptor<String> endpoints = ArgumentCaptor.forClass(String.class);
        verify(client, atLeastOnce()).executeGet(endpoints.capture(), eq(AuthorizationServerMetadata.class));
        assertTrue(endpoints.getAllValues().contains(OIDC_APPEND_URL),
                "OIDC path-append discovery URL must be among the queried fallback endpoints");
    }

    @Test
    void failsWhenNoDiscoveryFormResolves() {
        when(client.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenThrow(notFound());

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getAuthorizationServerMetadata(RESOURCE_ID, BASE_URL + "/mcp", protectedResourceMetadata(), true));
        assertEquals(
                "The MCP server for Resource: %s does not support OAuth authentication.".formatted(RESOURCE_ID),
                exception.getMessage());
    }

    @Test
    void failsWhenMetadataHasNoRegistrationEndpoint() {
        when(client.executeGet(anyString(), eq(AuthorizationServerMetadata.class)))
                .thenThrow(notFound());
        when(client.executeGet(eq(OIDC_APPEND_URL), eq(AuthorizationServerMetadata.class)))
                .thenReturn(metadata(null));

        IllegalArgumentException exception = assertThrows(
                IllegalArgumentException.class,
                () -> service.getAuthorizationServerMetadata(RESOURCE_ID, BASE_URL + "/mcp", protectedResourceMetadata(), true));
        assertEquals(
                "The MCP server for Resource: %s does not support dynamic client registration.".formatted(RESOURCE_ID),
                exception.getMessage());
    }

    private static AuthorizationServerProtectedResourceMetadata protectedResourceMetadata() {
        AuthorizationServerProtectedResourceMetadata metadata = new AuthorizationServerProtectedResourceMetadata();
        metadata.setAuthorizationServers(List.of(ISSUER));
        return metadata;
    }

    private static AuthorizationServerMetadata metadata(String registrationEndpoint) {
        return AuthorizationServerMetadata.builder()
                .issuer(ISSUER)
                .authorizationEndpoint(ISSUER + "/protocol/openid-connect/auth")
                .tokenEndpoint(ISSUER + "/protocol/openid-connect/token")
                .registrationEndpoint(registrationEndpoint)
                .build();
    }

    private static HttpException notFound() {
        return new HttpException(HttpStatus.NOT_FOUND, "Not Found");
    }
}
