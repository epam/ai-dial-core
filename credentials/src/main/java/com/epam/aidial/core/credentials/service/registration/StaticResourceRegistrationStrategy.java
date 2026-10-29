package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import com.epam.aidial.core.credentials.service.metadata.ProtectedResourceMetadataService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;

/**
 * Strategy for performing static registration of a protected resource.
 *
 * <p>
 * This implementation of {@link ResourceRegistrationStrategy} handles static registration
 * by determining the resource's properties (authorization, token, code challenge method and scopes supported)
 * either through the provided {@link ResourceAuthSettings} or by dynamically resolving them
 * using metadata from the {@link ProtectedResourceMetadataService} and {@link AuthorizationServerMetadataService}.
 * </p>
 *
 * <p>
 * If essential properties (authorization, token, code challenge method, scopes supported) are missing in the
 * settings, the strategy attempts to fetch metadata from the authorization server to fill
 * in the missing information. If these endpoints cannot be resolved, the registration fails
 * with an {@link IllegalArgumentException}.
 * </p>
 */
@Slf4j
@AllArgsConstructor
public class StaticResourceRegistrationStrategy implements ResourceRegistrationStrategy {

    private final AuthorizationServerMetadataService authorizationServerMetadataService;
    private final ProtectedResourceMetadataService protectedResourceMetadataService;

    /**
     * Registers a protected resource using static configuration settings.
     *
     * <p>
     * Attempts to register a resource using the provided {@link ResourceAuthSettings} (static settings),
     * but fills in missing information (authorization endpoint, token endpoint, code challenge method, scopes supported)
     * by retrieving metadata from the authorization server if necessary.
     * </p>
     *
     * @param resourceId          The unique identifier of the protected resource.
     * @param resourceEndpoint    The base endpoint URL of the protected resource.
     * @param resourceAuthSettings The static authentication settings provided for the resource.
     * @return A {@link ClientRegistration} containing the complete registration details.
     * @throws IllegalArgumentException If any of the required endpoints (authorizationEndpoint,
     *                                  tokenEndpoint) cannot be resolved.
     */
    @Override
    public ClientRegistration register(String resourceId, String resourceEndpoint, ResourceAuthSettings resourceAuthSettings) {
        log.info("Start static registration for Resource: {}", resourceId);

        AuthorizationServerProtectedResourceMetadata protectedResourceMetadata = null;
        String resource = StringUtils.trimToNull(resourceAuthSettings.getResource());
        String authorizationEndpoint = resourceAuthSettings.getAuthorizationEndpoint();
        String tokenEndpoint = resourceAuthSettings.getTokenEndpoint();
        String codeChallengeMethod = resourceAuthSettings.getCodeChallengeMethod();
        List<String> supportedScopes = resourceAuthSettings.getScopesSupported();

        if (authorizationEndpoint == null || tokenEndpoint == null
                || codeChallengeMethod == null || supportedScopes == null) {
            log.debug("AuthorizationEndpoint: {}, TokenEndpoint: {}, CodeChallengeMethod: {}, SupportedScopes: {}. "
                            + "Trying to retrieve AS Metadata.",
                    authorizationEndpoint, tokenEndpoint, codeChallengeMethod, supportedScopes);

            protectedResourceMetadata = protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint);

            AuthorizationServerMetadata authServerMetadata = authorizationServerMetadataService.getAuthorizationServerMetadata(
                    resourceId, resourceEndpoint, protectedResourceMetadata, false);

            if (supportedScopes == null || supportedScopes.isEmpty()) {
                supportedScopes = collectSupportedScopes(protectedResourceMetadata, authServerMetadata);
            }

            if (authServerMetadata != null) {
                authorizationEndpoint = Optional.ofNullable(authorizationEndpoint)
                        .orElse(authServerMetadata.getAuthorizationEndpoint());
                tokenEndpoint = Optional.ofNullable(tokenEndpoint)
                        .orElse(authServerMetadata.getTokenEndpoint());
                codeChallengeMethod = Optional.ofNullable(codeChallengeMethod)
                        .orElse(getCodeChallengeMethod(authServerMetadata).orElse(null));
            }
        } else if (resource == null) {
            protectedResourceMetadata = fetchProtectedResourceMetadataQuietly(resourceId, resourceEndpoint);
        }

        if (resource == null && protectedResourceMetadata != null) {
            resource = protectedResourceMetadata.getResource();
        }

        if (authorizationEndpoint == null || tokenEndpoint == null) {
            throw new IllegalArgumentException("Static resource registration requires: authorizationEndpoint, tokenEndpoint");
        }

        ClientRegistration clientRegistration = ClientRegistration.builder()
                .resourceId(resourceId)
                .clientId(resourceAuthSettings.getClientId())
                .clientSecret(resourceAuthSettings.getClientSecret())
                .redirectUri(resourceAuthSettings.getRedirectUri())
                .authorizationEndpoint(authorizationEndpoint)
                .tokenEndpoint(tokenEndpoint)
                .resource(resource)
                .codeChallengeMethod(codeChallengeMethod)
                .scopesSupported(supportedScopes)
                .tokenEndpointAuthMethod(resourceAuthSettings.getTokenEndpointAuthMethod())
                .build();

        log.info("Finished static registration for Resource: {}", resourceId);
        return clientRegistration;
    }

    /**
     * Static clients often sit behind authorization servers outside the MCP spec, so the endpoint is never guessed
     * as the resource indicator: an unpublished one is left unset and the token requests stay as they were. For the
     * same reason a failed lookup must not fail a registration that never needed it.
     */
    private AuthorizationServerProtectedResourceMetadata fetchProtectedResourceMetadataQuietly(String resourceId,
                                                                                              String resourceEndpoint) {
        try {
            return protectedResourceMetadataService.getProtectedResourceMetadata(resourceId, resourceEndpoint);
        } catch (RuntimeException e) {
            log.info("No protected resource metadata for Resource: {}, registering without a resource indicator: {}",
                    resourceId, e.getMessage());
            return null;
        }
    }
}
