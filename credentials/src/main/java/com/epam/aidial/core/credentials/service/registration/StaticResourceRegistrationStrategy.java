package com.epam.aidial.core.credentials.service.registration;

import com.epam.aidial.core.config.ResourceAuthSettings;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.ClientRegistration;
import com.epam.aidial.core.credentials.service.metadata.AuthorizationServerMetadataService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

/**
 * Strategy for performing static registration of a protected resource.
 *
 * <p>
 * This implementation of {@link ResourceRegistrationStrategy} handles static registration
 * by determining the resource's endpoints (authorization, token, and code challenge method)
 * either through the provided {@link ResourceAuthSettings} or by dynamically resolving them
 * using metadata from the {@link AuthorizationServerMetadataService}.
 * </p>
 *
 * <p>
 * If essential endpoints (authorization, token, code challenge method) are missing in the
 * settings, the strategy attempts to fetch metadata from the authorization server to fill
 * in the missing information. If these endpoints cannot be resolved, the registration fails
 * with an {@link IllegalArgumentException}.
 * </p>
 */
@Slf4j
@AllArgsConstructor
public class StaticResourceRegistrationStrategy implements ResourceRegistrationStrategy {

    private final AuthorizationServerMetadataService authorizationServerMetadataService;

    /**
     * Registers a protected resource using static configuration settings.
     *
     * <p>
     * Attempts to register a resource using the provided {@link ResourceAuthSettings} (static settings),
     * but fills in missing information (authorization endpoint, token endpoint, code challenge method)
     * by retrieving metadata from the authorization server if necessary.
     * </p>
     *
     * @param resourceId          The unique identifier of the protected resource.
     * @param resourceEndpoint    The base endpoint URL of the protected resource.
     * @param resourceAuthSettings The static authentication settings provided for the resource.
     * @return A {@link ClientRegistration} containing the complete registration details.
     * @throws IllegalArgumentException If any of the required endpoints (authorizationEndpoint,
     *                                  tokenEndpoint, codeChallengeMethod) cannot be resolved.
     */
    @Override
    public ClientRegistration register(String resourceId, String resourceEndpoint, ResourceAuthSettings resourceAuthSettings) {
        log.info("Start static registration for Resource: {}", resourceId);

        String authorizationEndpoint = resourceAuthSettings.getAuthorizationEndpoint();
        String tokenEndpoint = resourceAuthSettings.getTokenEndpoint();
        String codeChallengeMethod = resourceAuthSettings.getCodeChallengeMethod();

        if (authorizationEndpoint == null || tokenEndpoint == null || codeChallengeMethod == null) {
            log.debug("AuthorizationEndpoint: {}, TokenEndpoint: {}, CodeChallengeMethod: {}. Trying to retrieve AS Metadata.",
                    authorizationEndpoint, tokenEndpoint, codeChallengeMethod);

            AuthorizationServerMetadata authServerMetadata = authorizationServerMetadataService.getAuthorizationServerMetadata(
                    resourceId, resourceEndpoint, false);

            if (authServerMetadata != null) {
                authorizationEndpoint = Optional.ofNullable(authServerMetadata.getAuthorizationEndpoint())
                        .orElse(authorizationEndpoint);
                tokenEndpoint = Optional.ofNullable(authServerMetadata.getTokenEndpoint())
                        .orElse(tokenEndpoint);
                codeChallengeMethod = Optional.ofNullable(getCodeChallengeMethod(authServerMetadata))
                        .orElse(codeChallengeMethod);
            }
        }

        if (authorizationEndpoint == null || tokenEndpoint == null || codeChallengeMethod == null) {
            throw new IllegalArgumentException("Static resource registration requires: authorizationEndpoint, tokenEndpoint, codeChallengeMethod");
        }

        ClientRegistration clientRegistration = ClientRegistration.builder()
                .resourceId(resourceId)
                .clientId(resourceAuthSettings.getClientId())
                .clientSecret(resourceAuthSettings.getClientSecret())
                .redirectUri(resourceAuthSettings.getRedirectUri())
                .authorizationEndpoint(authorizationEndpoint)
                .tokenEndpoint(tokenEndpoint)
                .codeChallengeMethod(codeChallengeMethod)
                .build();

        log.info("Finished static registration for Resource: {}", resourceId);
        return clientRegistration;
    }
}
