package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationRequest;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationResponse;
import com.epam.aidial.core.credentials.exception.CredentialsInternalException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.nimbusds.oauth2.sdk.pkce.CodeChallengeMethod;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.util.List;

@Slf4j
@RequiredArgsConstructor
public class ResourceRegistrationService {
    private static final String AUTH_SERVER_ENDPOINT = "%s/.well-known/oauth-authorization-server";
    private static final String PROTECTED_RESOURCE_ENDPOINT = "%s/.well-known/oauth-protected-resource";
    private static final String AUTHORIZE_ENDPOINT = "%s/authorize";
    private static final String TOKEN_ENDPOINT = "%s/token";

    private final ResourceAuthorizationClient resourceAuthorizationClient;

    public ClientRegistration createDynamicResourceRegistration(String resourceId,
                                                                String resourceEndpoint,
                                                                String resourceRedirectUri) {
        log.info("Start Resource: {} registration.", resourceId);

        String baseResourceEndpoint = getBaseResourceEndpoint(resourceEndpoint);
        log.debug("Resource {} base endpoint: {}", resourceId, baseResourceEndpoint);

        String resourceAuthServerEndpoint = getResourceAuthorizationServerEndpoint(baseResourceEndpoint);
        AuthorizationServerMetadata resourceAuthorizationServerMetadata = getResourceAuthorizationServerMetadata(resourceAuthServerEndpoint);

        ClientRegistrationRequest clientRegistrationRequest = ClientRegistrationRequest.builder()
                .clientName(resourceId)
                .redirectUris(List.of(resourceRedirectUri))
                .build();

        ClientRegistrationResponse clientRegistrationResponse = resourceAuthorizationClient.executePost(
                resourceAuthorizationServerMetadata.getRegistrationEndpoint(),
                clientRegistrationRequest,
                ContentType.APPLICATION_JSON.toString(),
                ClientRegistrationResponse.class);

        ClientRegistration resourceRegistration = createResourceRegistration(
                clientRegistrationResponse,
                resourceAuthorizationServerMetadata);

        log.info("Finished Resource: {} registration.", resourceId);
        return resourceRegistration;
    }

    public ClientRegistration createStaticResourceRegistration(String resourceId,
                                                               String resourceEndpoint,
                                                               ResourceAuthSettings resourceAuthSettings) {
        String baseResourceEndpoint = getBaseResourceEndpoint(resourceEndpoint);
        log.debug("Resource {} base endpoint: {}", resourceId, baseResourceEndpoint);

        String resourceAuthServerEndpoint = getResourceAuthorizationServerEndpoint(baseResourceEndpoint);

        String resourceAuthorizationEndpoint;
        String tokenEndpoint;
        String codeChallengeMethodSupported;
        try {
            AuthorizationServerMetadata authorizationServerMetadata = getResourceAuthorizationServerMetadata(resourceAuthServerEndpoint);
            resourceAuthorizationEndpoint = authorizationServerMetadata.getAuthorizationEndpoint();
            tokenEndpoint = authorizationServerMetadata.getTokenEndpoint();
            codeChallengeMethodSupported = getCodeChallengeMethod(authorizationServerMetadata);
        } catch (HttpException e) {
            log.error(e.getMessage(), e);
            resourceAuthorizationEndpoint = AUTHORIZE_ENDPOINT.formatted(baseResourceEndpoint);
            tokenEndpoint = TOKEN_ENDPOINT.formatted(baseResourceEndpoint);
            codeChallengeMethodSupported = CodeChallengeMethod.S256.getValue();
        }

        return ClientRegistration.builder()
            .resourceId(resourceId)
            .clientId(resourceAuthSettings.getClientId())
            .clientSecret(resourceAuthSettings.getClientSecret())
            .redirectUri(resourceAuthSettings.getRedirectUri())
            .authorizationEndpoint(resourceAuthorizationEndpoint)
            .tokenEndpoint(tokenEndpoint)
            .codeChallengeMethod(codeChallengeMethodSupported)
            .build();
    }

    private String getBaseResourceEndpoint(String resourceEndpoint) {
        return resourceEndpoint.endsWith("/mcp")
               ? resourceEndpoint.substring(0, resourceEndpoint.lastIndexOf("/mcp"))
               : resourceEndpoint;
    }

    private ClientRegistration createResourceRegistration(ClientRegistrationResponse clientRegistrationResponse,
                                                          AuthorizationServerMetadata authorizationServerMetadata) {
        return ClientRegistration.builder()
            .resourceId(clientRegistrationResponse.getClientName())
            .clientId(clientRegistrationResponse.getClientId())
            .clientSecret(clientRegistrationResponse.getClientSecret())
            .redirectUri(clientRegistrationResponse.getRedirectUris().getFirst())
            .authorizationEndpoint(authorizationServerMetadata.getAuthorizationEndpoint())
            .tokenEndpoint(authorizationServerMetadata.getTokenEndpoint())
            .codeChallengeMethod(getCodeChallengeMethod(authorizationServerMetadata))
            .build();
    }

    //TODO: change default method to S256
    private String getCodeChallengeMethod(AuthorizationServerMetadata authorizationServerMetadata) {
        List<String> codeChallengeMethodsSupported = authorizationServerMetadata.getCodeChallengeMethodsSupported();
        return codeChallengeMethodsSupported.contains(CodeChallengeMethod.PLAIN.getValue())
                                     ? CodeChallengeMethod.PLAIN.getValue()
                                     : codeChallengeMethodsSupported.getFirst();
    }

    private String getResourceAuthorizationServerEndpoint(String baseResourceEndpoint) {
        String resourceAuthServerBaseEndpoint;
        try {
            AuthorizationServerProtectedResourceMetadata authorizationServerProtectedResourceMetadata =
                    getAuthorizationServerProtectedResourceMetadata(baseResourceEndpoint);

            List<String> authorizationServers = authorizationServerProtectedResourceMetadata.getAuthorizationServers();
            if (authorizationServers == null || authorizationServers.isEmpty()) {
                throw new RuntimeException("No authorization servers defined for dynamic client registration.");
            }
            //TODO: should we get the first one?
            resourceAuthServerBaseEndpoint = authorizationServers.getFirst();
        } catch (HttpException e) {
            if (e.getStatus().equals(HttpStatus.NOT_FOUND)) {
                resourceAuthServerBaseEndpoint = baseResourceEndpoint;
            } else {
                log.error("Error getting authorization servers for dynamic client registration: {}", e.getMessage(), e);
                throw new CredentialsInternalException("Error getting authorization servers for dynamic client registration.", e);
            }
        }
        String authServerWellKnownEndpoint = AUTH_SERVER_ENDPOINT.formatted(resourceAuthServerBaseEndpoint);
        log.debug("AuthServerEndpoint: {}", authServerWellKnownEndpoint);
        return authServerWellKnownEndpoint;
    }

    private AuthorizationServerMetadata getResourceAuthorizationServerMetadata(String resourceAuthServerEndpoint) {
        AuthorizationServerMetadata authorizationServerMetadata = resourceAuthorizationClient.executeGet(resourceAuthServerEndpoint,
                AuthorizationServerMetadata.class);
        log.debug("AuthorizationServerMetadata: {}", authorizationServerMetadata);
        return authorizationServerMetadata;
    }

    private AuthorizationServerProtectedResourceMetadata getAuthorizationServerProtectedResourceMetadata(String baseResourceEndpoint) {
        String protectedResourceEndpoint = String.format(PROTECTED_RESOURCE_ENDPOINT, baseResourceEndpoint);
        AuthorizationServerProtectedResourceMetadata authorizationServerProtectedResourceMetadata =
                resourceAuthorizationClient.executeGet(protectedResourceEndpoint, AuthorizationServerProtectedResourceMetadata.class);
        log.debug("AuthorizationServerProtectedResourceMetadata: {}", authorizationServerProtectedResourceMetadata);
        return authorizationServerProtectedResourceMetadata;
    }
}

