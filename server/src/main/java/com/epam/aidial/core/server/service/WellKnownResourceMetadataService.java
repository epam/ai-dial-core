package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.data.wellknown.ResourceMetadata;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import io.vertx.core.net.HostAndPort;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing OAuth 2.0 Protected Resource metadata, as defined in
 * <a href="https://datatracker.ietf.org/doc/html/rfc9728">RFC 9728</a>.
 *
 * <p>This service provides:
 * <ul>
 *   <li>Construction of the absolute URI for the resource metadata document.</li>
 *   <li>Resolution of the original resource identifier from incoming requests.</li>
 *   <li>Assembly of the {@link ResourceMetadata} object to be returned from the metadata endpoint.</li>
 * </ul>
 *
 * <p>RFC 9728 specifies that a protected resource should expose metadata at a well-known path:
 * <code>/.well-known/oauth-protected-resource</code>.
 * This metadata allows clients to discover authorization servers and identify the resource itself.
 */
@Slf4j
public class WellKnownResourceMetadataService {

    private static final String RESOURCE_METADATA_PATH = "/.well-known/oauth-protected-resource";

    private final List<String> authorizationServers;
    private final String resourceHost;

    public WellKnownResourceMetadataService(JsonObject toolsetSettings) {
        JsonObject security = toolsetSettings != null ? toolsetSettings.getJsonObject("security") : null;

        this.authorizationServers = (security != null && security.containsKey("authorizationServers"))
                ? parseAuthorizationServers(security.getValue("authorizationServers"))
                : Collections.emptyList();

        this.resourceHost = (security != null) ? security.getString("resourceHost") : null;
    }

    private static List<String> parseAuthorizationServers(Object authServerValue) {
        if (authServerValue instanceof String str) {
            return List.of(str);
        }
        if (authServerValue instanceof JsonArray array) {
            return array.stream()
                    .map(Object::toString)
                    .toList();
        }
        if (authServerValue == null) {
            return Collections.emptyList();
        }
        throw new IllegalArgumentException("'authorizationServers' must be either a String or an Array");
    }

    /**
     * Builds the absolute URI of the resource metadata document.
     *
     * <p>The URI is constructed by:
     * <ol>
     *   <li>Using the scheme <code>https</code> and the configured {@code resourceHost}
     *       (or the request authority if not configured).</li>
     *   <li>Appending the well-known metadata path:
     *       <code>/.well-known/oauth-protected-resource</code>.</li>
     *   <li>Appending the original request path.</li>
     * </ol>
     *
     * <p>Example:
     * <pre>
     * Request: https://example.com/test/123
     * Metadata URI: https://example.com/.well-known/oauth-protected-resource/test/123
     * </pre>
     *
     * @param request The incoming HTTP request.
     * @return The absolute metadata document URI.
     */
    public Optional<String> resolveResourceMetadataPath(HttpServerRequest request) {
        if (isDisabled()) {
            return Optional.empty();
        }

        String path = request.path() == null ? "" : request.path();

        HostAndPort authority = request.authority();
        String host = resourceHost != null ? resourceHost : authority.toString();

        return Optional.of("https://" + host + RESOURCE_METADATA_PATH + path);
    }

    /**
     * Creates a {@link ResourceMetadata} instance for the given request.
     *
     * <p>Populates:
     * <ul>
     *   <li><b>authorization_servers</b> – Configured list of authorization server issuers.</li>
     *   <li><b>resource</b> – The identifier of the protected resource, resolved from the request.</li>
     * </ul>
     *
     * @param request The incoming HTTP request.
     * @return The populated metadata object for JSON serialization.
     */
    public Optional<ResourceMetadata> resolveResourceMetadata(HttpServerRequest request) {
        if (isDisabled()) {
            return Optional.empty();
        }

        ResourceMetadata metadata = new ResourceMetadata();
        metadata.setAuthorizationServers(authorizationServers);
        metadata.setResource(resolveResource(request));
        return Optional.of(metadata);
    }

    /**
     * Resolves the protected resource identifier from a request to the metadata endpoint.
     *
     * <p>Removes the <code>/.well-known/oauth-protected-resource</code> prefix from the request path
     * and returns the resulting URI. If the path is exactly the metadata path, the identifier
     * is the root of the host.
     *
     * <p>Examples:
     * <pre>
     * Request: https://api.example.com/.well-known/oauth-protected-resource/test/123
     * Result:  https://api.example.com/test/123
     *
     * Request: https://api.example.com/.well-known/oauth-protected-resource
     * Result:  https://api.example.com
     * </pre>
     *
     * @param request The incoming HTTP request.
     * @return The original protected resource URI.
     * @throws IllegalArgumentException If the path does not match the expected metadata path.
     */
    private String resolveResource(HttpServerRequest request) {
        String path = request.path();

        String resourceSegment;

        if (path.equals(RESOURCE_METADATA_PATH)) {
            resourceSegment = ""; // The resource is the root of the host

        } else if (path.startsWith(RESOURCE_METADATA_PATH + "/")) {
            resourceSegment = path.substring(RESOURCE_METADATA_PATH.length());
        } else {
            throw new IllegalArgumentException("Invalid path. Path must be or start with " + RESOURCE_METADATA_PATH + ", but was " + path);
        }

        HostAndPort authority = request.authority();
        String host = resourceHost != null ? resourceHost : authority.toString();

        return "https://" + host + resourceSegment;
    }

    private boolean isDisabled() {
        return authorizationServers.isEmpty();
    }

}
