package com.epam.aidial.core.credentials.util;

import lombok.experimental.UtilityClass;

import java.net.URI;
import java.net.URISyntaxException;

@UtilityClass
public class ResourceEndpointUtil {

    /**
     * Extracts the base URL (scheme + host) from the given input URL, ignoring the path, query, and fragment components.
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

            if (scheme == null || host == null) {
                throw new IllegalArgumentException("Invalid URL: Missing scheme or host.");
            }

            return scheme + "://" + host;

        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Invalid URL format: " + url, e);
        }
    }

    /**
     * Builds the resource metadata endpoint based on a resource endpoint.
     *
     * @param resourceEndpoint The resource endpoint
     * @param wellKnownSuffix The well-known URI suffix
     * @return The constructed metadata endpoint.
     */
    public static String buildMetadataEndpoint(String resourceEndpoint,
                                               String wellKnownSuffix,
                                               boolean ignorePathSuffix) {
        try {
            URI resourceUri = new URI(resourceEndpoint);

            String scheme = resourceUri.getScheme();
            String host = resourceUri.getHost();
            String path = resourceUri.getPath();

            if (path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }

            return String.format("%s://%s/.well-known/%s%s",
                    scheme,
                    host,
                    wellKnownSuffix,
                    path.isEmpty() || ignorePathSuffix ? "" : path
            );
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Resource endpoint is not valid URI");
        }
    }
}

