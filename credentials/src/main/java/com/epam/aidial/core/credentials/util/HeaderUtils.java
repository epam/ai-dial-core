package com.epam.aidial.core.credentials.util;

import lombok.experimental.UtilityClass;

import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@UtilityClass
public class HeaderUtils {

    private static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";
    private static final String RESOURCE_METADATA_PARAMETER = "resource_metadata=";

    /**
     * Handles extracting the resource metadata URL from the WWW-Authenticate header.
     *
     * @param headers Map of response headers.
     * @return Optional metadata URL if found.
     */
    public static Optional<String> extractMetadataUrl(Map<String, String> headers) {
        if (headers == null || headers.isEmpty() || !headers.containsKey(WWW_AUTHENTICATE_HEADER)) {
            return Optional.empty();
        }
        String authHeader = headers.get(WWW_AUTHENTICATE_HEADER);
        return extractMetadataUrl(authHeader);
    }

    /**
     * Extracts the resource metadata URL string from the WWW-Authenticate header string.
     *
     * @param authHeader Header string to search for metadata URL.
     * @return Optional metadata URL if found.
     */
    private static Optional<String> extractMetadataUrl(String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            return Optional.empty();
        }

        int startIndex = authHeader.indexOf(RESOURCE_METADATA_PARAMETER);
        if (startIndex == -1) {
            return Optional.empty();
        }

        startIndex += RESOURCE_METADATA_PARAMETER.length();

        while (startIndex < authHeader.length() && Character.isWhitespace(authHeader.charAt(startIndex))) {
            startIndex++;
        }

        if (authHeader.charAt(startIndex) == '"') {
            int endIndex = authHeader.indexOf("\"", startIndex + 1);
            if (endIndex > startIndex) {
                return Optional.of(authHeader.substring(startIndex + 1, endIndex));
            } else {
                return Optional.empty();
            }
        }

        int endIndex = authHeader.indexOf(" ", startIndex);
        if (endIndex == -1) {
            endIndex = authHeader.length();
        }

        String metadataUrl = authHeader.substring(startIndex, endIndex).trim();
        return metadataUrl.isEmpty() ? Optional.empty() : Optional.of(metadataUrl);
    }

    public static Map<String, String> convertHttpHeadersToMap(HttpHeaders httpHeaders) {
        return httpHeaders.map().entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.join(",", entry.getValue())
                ));
    }
}
