package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.credentials.TokenEndpointAuthMethod;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationRequest;
import com.epam.aidial.core.credentials.data.registration.ClientRegistrationResponse;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Strategy for performing dynamic registration of a protected resource.
 *
 * <p>
 * This implementation of {@link ResourceRegistrationStrategy} performs dynamic registration by utilizing
 * the dynamic client registration functionality of the authorization server. It discovers the
 * registration endpoint, authorization server metadata, and other required details dynamically.
 * </p>
 *
 * <p>
 * The strategy begins by retrieving protected resource metadata using the {@link ProtectedResourceMetadataService}.
 * Then it retrieves authorization server metadata using the {@link AuthorizationServerMetadataService}.
 * Then it sends a dynamic registration request to the server's registration endpoint.
 * Finally, it constructs a complete {@link ClientRegistration}
 * object using the response from the server.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class DynamicResourceRegistrationStrategy implements ResourceRegistrationStrategy {

    private final AuthorizationServerMetadataService authorizationServerMetadataService;
    private final ResourceAuthorizationClient resourceAuthorizationClient;
    private final ProtectedResourceMetadataService protectedResourceMetadataService;
    private final List<String> allowedRedirectUris;

    /**
     * Registers a protected resource dynamically using the authorization server's dynamic client registration
     * functionality.
     *
     * <p>
     * This method begins by retrieving the metadata from the authorization server. It constructs a
     * {@link ClientRegistrationRequest} to dynamically register the client with the authorization system.
     * The registration response is used to build a {@link ClientRegistration} that contains all the details
     * required for OAuth 2.0 or OpenID Connect authentication.
     * </p>
     *
     * @param resourceId          The unique identifier of the protected resource.
     * @param resourceEndpoint    The base URL of the protected resource's endpoint.
     * @param resourceAuthSettings The static authentication settings provided for the resource.
     * @return A {@link ClientRegistration} object containing the complete registration details for the resource.
     * @throws IllegalArgumentException If the registration endpoint, authorization server metadata, or required details are missing.
     */
    @Override
    public ClientRegistration register(String resourceId, String resourceEndpoint, ResourceAuthSettings resourceAuthSettings) {
        log.info("Start dynamic registration for Resource: {}", resourceId);

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata =
                protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint);

        AuthorizationServerMetadata authServerMetadata = authorizationServerMetadataService.getAuthorizationServerMetadata(
                resourceId, resourceEndpoint, protectedResourceMetadata, true);

        List<String> supportedScopes = collectSupportedScopes(protectedResourceMetadata, authServerMetadata);

        ClientRegistrationRequest clientRegistrationRequest = ClientRegistrationRequest.builder()
                .clientName(resourceId)
                .redirectUris(collectRedirectUris(resourceAuthSettings))
                .build();

        ClientRegistrationResponse clientRegistrationResponse = resourceAuthorizationClient.executePost(
                authServerMetadata.getRegistrationEndpoint(),
                clientRegistrationRequest,
                ContentType.APPLICATION_JSON.toString(),
                ClientRegistrationResponse.class);

        // Reject methods DIAL can't honor (e.g. private_key_jwt) at registration time so the
        // operator gets a clear error before any end-user sign-in attempt. Honor the method the AS
        // advertises; when it omits it, infer from the issued client type — a client registered
        // without a secret is public (none), otherwise client_secret_basic (RFC 7591 §2). The
        // persisted value always reflects what DIAL will use.
        String tokenEndpointAuthMethod = TokenEndpointAuthMethod.resolve(
                clientRegistrationResponse.getTokenEndpointAuthMethod(),
                clientRegistrationResponse.getClientSecret()).value();

        ClientRegistration clientRegistration = ClientRegistration.builder()
                .resourceId(clientRegistrationResponse.getClientName())
                .clientId(clientRegistrationResponse.getClientId())
                .clientSecret(clientRegistrationResponse.getClientSecret())
                .redirectUri(clientRegistrationResponse.getRedirectUris().getFirst())
                .authorizationEndpoint(authServerMetadata.getAuthorizationEndpoint())
                .tokenEndpoint(authServerMetadata.getTokenEndpoint())
                .scopesSupported(supportedScopes)
                .tokenEndpointAuthMethod(tokenEndpointAuthMethod)
                .build();

        Optional<String> codeChallengeMethod = getCodeChallengeMethod(authServerMetadata);
        codeChallengeMethod.ifPresent(clientRegistration::setCodeChallengeMethod);

        log.info("Finished dynamic registration for Resource: {}", resourceId);
        return clientRegistration;
    }

    private List<String> collectRedirectUris(ResourceAuthSettings resourceAuthSettings) {
        List<String> uris = new ArrayList<>(allowedRedirectUris);
        String resourceRedirectUri = resourceAuthSettings.getRedirectUri();
        if (resourceRedirectUri != null && !uris.contains(resourceRedirectUri)) {
            uris.add(resourceRedirectUri);
        }
        return uris;
    }
}
