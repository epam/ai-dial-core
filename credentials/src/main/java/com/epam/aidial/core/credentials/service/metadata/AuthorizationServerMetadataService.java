package com.epam.aidial.core.credentials.service.metadata;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerMetadata;
import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.util.FallbackHandler;
import com.epam.aidial.core.credentials.util.ResourceEndpointUtil;
import com.epam.aidial.core.credentials.validation.AuthorizationServerMetadataValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Service class for fetching and validating metadata about an authorization server.
 *
 * <p>
 * This service dynamically discovers metadata of an authorization server by building a set of fallback
 * endpoints and querying them sequentially until a valid response is obtained. It validates the metadata
 * and handles additional requirements such as dynamic client registration.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class AuthorizationServerMetadataService {

    private static final String WELL_KNOWN_AUTH_SERVER_SUFFIX = "oauth-authorization-server";
    private static final String WELL_KNOWN_OPENID_CONFIG_SUFFIX = "openid-configuration";

    private final ResourceAuthorizationClient resourceAuthorizationClient;
    private final AuthorizationServerMetadataValidator authorizationServerMetadataValidator;

    /**
     * Fetches the {@link AuthorizationServerMetadata} for the given resource.
     *
     * <p>
     * The method attempts to discover metadata by querying a set of fallback endpoints
     * derived from the resource's information and metadata. If the metadata indicates support
     * for dynamic client registration and the `forDynamicRegistration` flag is enabled,
     * but no registration endpoint is provided, an exception is thrown.
     * </p>
     *
     * @param resourceId          The unique identifier of the resource.
     * @param resourceEndpoint    The base URL of the resource endpoint.
     * @param protectedResourceMetadata The metadata for the protected resource.
     * @param forDynamicRegistration Indicates whether dynamic client registration is required.
     * @return The {@link AuthorizationServerMetadata} containing the authorization server's details.
     * @throws IllegalArgumentException If the metadata cannot be fetched or if required properties
     *                                  for dynamic registration are missing.
     */
    public AuthorizationServerMetadata getAuthorizationServerMetadata(String resourceId,
                                                                      String resourceEndpoint,
                                                                      AuthorizationServerProtectedResourceMetadata protectedResourceMetadata,
                                                                      boolean forDynamicRegistration) {
        Set<String> fallbackEndpoints = new LinkedHashSet<>();
        addAuthServerEndpointsFromProtectedResourceMetadata(protectedResourceMetadata, fallbackEndpoints);
        addAuthServerEndpoints(resourceEndpoint, fallbackEndpoints);

        AuthorizationServerMetadata metadata = FallbackHandler.executeWithFallback(
                fallbackEndpoints,
                endpoint -> resourceAuthorizationClient.executeGet(endpoint, AuthorizationServerMetadata.class),
                (endpoint, result) -> authorizationServerMetadataValidator.validate(result, endpoint)
        );

        if (metadata != null
                && forDynamicRegistration
                && metadata.getRegistrationEndpoint() == null) {
            throw new IllegalArgumentException(
                    "The MCP server for Resource: %s does not support dynamic client registration.".formatted(resourceId)
            );
        }

        if (metadata == null
                && forDynamicRegistration) {
            throw new IllegalArgumentException(
                    "The MCP server for Resource: %s does not support OAuth authentication.".formatted(resourceId)
            );
        }

        log.debug("Fetched Authorization Server Metadata: {}", metadata);
        return metadata;
    }

    /**
     * Adds authorization server endpoints from the protected resource metadata to the fallback set.
     *
     * @param protectedResourceMetadata The metadata for the protected resource.
     * @param fallbackEndpoints         The set of fallback endpoints to add to.
     */
    private void addAuthServerEndpointsFromProtectedResourceMetadata(AuthorizationServerProtectedResourceMetadata protectedResourceMetadata,
                                                                     Set<String> fallbackEndpoints) {
        if (protectedResourceMetadata != null) {
            protectedResourceMetadata.getAuthorizationServers()
                    .forEach(authServerEndpoint -> addAuthServerEndpoints(authServerEndpoint, fallbackEndpoints));
        }
    }

    /**
     * Adds the well-known endpoints for a given resource or authorization server base URL.
     *
     * <p>
     * This method appends the following well-known paths to the base URL:
     * <ul>
     *     <li>{@code /.well-known/oauth-authorization-server}</li>
     *     <li>{@code /.well-known/openid-configuration}</li>
     * </ul>
     * For each path, both the default (no path suffix) and multi-tenant (with suffix) configurations are added.
     * </p>
     *
     * @param resourceEndpoint  The base URL of the resource or authorization server.
     * @param fallbackEndpoints The set of fallback endpoints to add to.
     */
    private void addAuthServerEndpoints(String resourceEndpoint,
                                        Set<String> fallbackEndpoints) {
        fallbackEndpoints.add(ResourceEndpointUtil.buildMetadataEndpoint(resourceEndpoint, WELL_KNOWN_AUTH_SERVER_SUFFIX, false));
        fallbackEndpoints.add(ResourceEndpointUtil.buildMetadataEndpoint(resourceEndpoint, WELL_KNOWN_AUTH_SERVER_SUFFIX, true));
        fallbackEndpoints.add(ResourceEndpointUtil.buildMetadataEndpoint(resourceEndpoint, WELL_KNOWN_OPENID_CONFIG_SUFFIX, false));
        fallbackEndpoints.add(ResourceEndpointUtil.buildMetadataEndpoint(resourceEndpoint, WELL_KNOWN_OPENID_CONFIG_SUFFIX, true));
        // OpenID Connect Discovery 1.0 path-append form (e.g. Keycloak: {issuer}/.well-known/openid-configuration)
        fallbackEndpoints.add(ResourceEndpointUtil.buildAppendedMetadataEndpoint(resourceEndpoint, WELL_KNOWN_OPENID_CONFIG_SUFFIX));
    }
}
