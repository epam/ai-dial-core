package com.epam.aidial.core.credentials.util;

import lombok.experimental.UtilityClass;

import java.net.URI;
import java.net.URISyntaxException;

@UtilityClass
public class ResourceEndpointUtil {

    /**
     * Extracts the base URL (scheme + host + port (optional)) from the given input URL, ignoring the path, query, and fragment components.
     *
     * @param url The resource endpoint URL
     * @return The base URL
     * @throws IllegalArgumentException If the URL is null, empty, or invalid.
     */
    public static String buildBaseResourceEndpoint(String url) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty.");
        }

        try {
            URI uri = new URI(url);

            String scheme = uri.getScheme();
            String host = uri.getHost();
            int port = uri.getPort();

            if (scheme == null || host == null) {
                throw new IllegalArgumentException("Invalid URL: Missing scheme or host.");
            }

            return String.format("%s://%s%s",
                    scheme,
                    host,
                    (port > 0 ? ":" + port : "")
            );

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL format: " + url, e);
        }
    }

    /**
     * Builds the resource metadata endpoint based on a resource endpoint.
     *
     * @param url The resource endpoint URL
     * @param wellKnownSuffix The well-known URI suffix
     * @return The constructed metadata endpoint.
     */
    public static String buildMetadataEndpoint(String url,
                                               String wellKnownSuffix,
                                               boolean ignorePathSuffix) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty.");
        }

        try {
            URI resourceUri = new URI(url);

            String scheme = resourceUri.getScheme();
            String host = resourceUri.getHost();
            String path = resourceUri.getPath();
            int port = resourceUri.getPort();

            if (scheme == null || host == null) {
                throw new IllegalArgumentException("Invalid URL: Missing scheme or host.");
            }

            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            return String.format("%s://%s%s/.well-known/%s%s",
                    scheme,
                    host,
                    (port > 0 ? ":" + port : ""),
                    wellKnownSuffix,
                    path.isEmpty() || ignorePathSuffix ? "" : path
            );
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Resource endpoint is not valid URI");
        }
    }

    /**
     * Builds the metadata endpoint by appending the well-known suffix to the end of the resource path,
     * following the OpenID Connect Discovery 1.0 convention (e.g. Keycloak: {@code {issuer}/.well-known/openid-configuration}).
     *
     * <p>This complements {@link #buildMetadataEndpoint}, which inserts the well-known component between
     * the host and path per RFC 8414.</p>
     *
     * @param url             The resource endpoint URL
     * @param wellKnownSuffix The well-known URI suffix
     * @return The constructed metadata endpoint.
     */
    public static String buildAppendedMetadataEndpoint(String url, String wellKnownSuffix) {
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("URL cannot be null or empty.");
        }

        try {
            URI resourceUri = new URI(url);

            String scheme = resourceUri.getScheme();
            String host = resourceUri.getHost();
            String path = resourceUri.getPath();
            int port = resourceUri.getPort();

            if (scheme == null || host == null) {
                throw new IllegalArgumentException("Invalid URL: Missing scheme or host.");
            }

            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            return String.format("%s://%s%s%s/.well-known/%s",
                    scheme,
                    host,
                    (port > 0 ? ":" + port : ""),
                    path,
                    wellKnownSuffix
            );
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Resource endpoint is not valid URI");
        }
    }
}

