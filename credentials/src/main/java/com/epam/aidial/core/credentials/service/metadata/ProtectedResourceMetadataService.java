package com.epam.aidial.core.credentials.service.metadata;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.util.FallbackHandler;
import com.epam.aidial.core.credentials.util.HeaderUtils;
import com.epam.aidial.core.credentials.util.ResourceEndpointUtil;
import com.epam.aidial.core.credentials.validation.ProtectedResourceMetadataValidator;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.core5.http.ContentType;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Service class for fetching and validating protected resource metadata.
 *
 * <p>
 * This class is responsible for dynamically discovering metadata for a protected resource
 * by attempting multiple fallback mechanisms, including:
 * <ul>
 *     <li>Using metadata URL retrieved from the `WWW-Authenticate` header (if available).</li>
 *     <li>Querying well-known endpoints generated from the resource's base URL.</li>
 * </ul>
 * </p>
 *
 * <p>
 * The service validates the retrieved metadata using {@link ProtectedResourceMetadataValidator}
 * before returning it to the caller.
 * </p>
 */
@Slf4j
@RequiredArgsConstructor
public class ProtectedResourceMetadataService {

    private static final String WELL_KNOWN_SUFFIX = "oauth-protected-resource";

    private final ResourceAuthorizationClient resourceAuthorizationClient;
    private final ProtectedResourceMetadataValidator protectedResourceMetadataValidator;

    /**
     * Fetches metadata for a protected resource, using fallback mechanisms if necessary.
     *
     * <p>
     * This method starts by attempting to fetch metadata using the `WWW-Authenticate` header
     * on a 401 Unauthorized response. If that fails, it generates fallback endpoints based
     * on the resource's base endpoint and queries them for metadata.
     * </p>
     *
     * @param resourceId       The unique identifier of the resource.
     * @param resourceEndpoint The base URL of the resource endpoint.
     * @return The {@link AuthorizationServerProtectedResourceMetadata} for the resource.
     * @throws IllegalArgumentException If the fetched metadata is invalid.
     */
    public AuthorizationServerProtectedResourceMetadata getProtectedResourceMetadata(String resourceId,
                                                                                     String resourceEndpoint) {
        log.info("Fetching protected resource metadata for resource: {}", resourceId);
        AuthorizationServerProtectedResourceMetadata metadata = tryFetchMetadataUsingHeader(resourceEndpoint);

        if (metadata != null) {
            protectedResourceMetadataValidator.validate(metadata, resourceEndpoint);
            return metadata;
        }

        Set<String> fallbackEndpoints = new LinkedHashSet<>();
        fallbackEndpoints.add(ResourceEndpointUtil.buildMetadataEndpoint(resourceEndpoint, WELL_KNOWN_SUFFIX, false));
        fallbackEndpoints.add(ResourceEndpointUtil.buildMetadataEndpoint(resourceEndpoint, WELL_KNOWN_SUFFIX, true));

        metadata = FallbackHandler.executeWithFallback(
                fallbackEndpoints,
                this::tryFetchMetadata,
                (endpoint, result) -> protectedResourceMetadataValidator.validate(result, endpoint));

        return metadata;
    }

    /**
     * Attempts to fetch metadata using the `WWW-Authenticate` header.
     *
     * <p>
     * This method sends a POST request to the resource endpoint. If the response contains
     * a 401 Unauthorized status code, the method extracts the `WWW-Authenticate` header
     * to locate the metadata URL and fetch the metadata from that endpoint.
     * </p>
     *
     * @param resourceEndpoint The base URL of the resource endpoint.
     * @return The {@link AuthorizationServerProtectedResourceMetadata}, or {@code null} if metadata cannot be resolved.
     * @throws HttpException If the HTTP request fails with a non-recoverable status.
     */
    private AuthorizationServerProtectedResourceMetadata tryFetchMetadataUsingHeader(String resourceEndpoint) {
        try {
            resourceAuthorizationClient.executePost(resourceEndpoint, "{}", ContentType.APPLICATION_JSON.toString(), Object.class);
        } catch (HttpException e) {
            HttpStatus httpExceptionStatus = e.getStatus();
            if (httpExceptionStatus.equals(HttpStatus.UNAUTHORIZED)) {
                Optional<String> metadataUrl = HeaderUtils.extractMetadataUrl(e.getHeaders());
                if (metadataUrl.isPresent()) {
                    log.debug("Retrieved metadata URL from WWW-Authenticate header: {}", metadataUrl.get());
                    return tryFetchMetadata(metadataUrl.get());
                }
                log.debug("401 Unauthorized at endpoint: {}. Proceeding to next fallback.", resourceEndpoint);
            } else if (httpExceptionStatus.equals(HttpStatus.NOT_FOUND)) {
                log.debug("404 Not Found at endpoint: {}. Proceeding to next fallback.", resourceEndpoint);
            } else {
                throw e;
            }
            return null;
        }
        log.debug("Resolving Resource Metadata endpoint for resource: {}", resourceEndpoint);
        return null;
    }

    /**
     * Attempts to fetch metadata from a given endpoint.
     *
     * <p>
     * This method sends a GET request to the specified metadata endpoint and parses the
     * response into {@link AuthorizationServerProtectedResourceMetadata}. Non-recoverable
     * HTTP status codes (e.g., server errors) result in an exception being thrown.
     * </p>
     *
     * @param endpoint The URL to query for metadata.
     * @return The {@link AuthorizationServerProtectedResourceMetadata}, or {@code null} if the request fails with a recoverable status.
     * @throws HttpException For unrecoverable HTTP statuses (e.g., 500-level errors).
     */
    private AuthorizationServerProtectedResourceMetadata tryFetchMetadata(String endpoint) {
        try {
            log.debug("Attempting to fetch metadata from endpoint: {}", endpoint);
            AuthorizationServerProtectedResourceMetadata metadata = resourceAuthorizationClient.executeGet(endpoint, AuthorizationServerProtectedResourceMetadata.class);
            log.debug("Successfully received metadata from endpoint: {}", endpoint);
            return metadata;
        } catch (HttpException e) {
            HttpStatus httpExceptionStatus = e.getStatus();
            if (httpExceptionStatus.equals(HttpStatus.UNAUTHORIZED)
                    || httpExceptionStatus.equals(HttpStatus.NOT_FOUND)) {
                log.debug("401 Unauthorized at endpoint: {}. Proceeding to next fallback.", endpoint);
            } else {
                throw e;
            }
        }
        return null;
    }
}