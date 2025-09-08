package com.epam.aidial.core.credentials.service.metadata;

import java.net.http.HttpHeaders;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class HttpHeadersHandler {

    private static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";
    private static final String RESOURCE_METADATA_PARAMETER = "resource_metadata=";

    /**
     * Handles extracting the resource metadata URL from the WWW-Authenticate header.
     *
     * @param headers Map of response headers.
     * @return Optional metadata URL if found.
     */
    public Optional<String> extractMetadataUrl(Map<String, String> headers) {
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
    private Optional<String> extractMetadataUrl(String authHeader) {
        if (authHeader == null || authHeader.isEmpty()) {
            return Optional.empty();
        }

        int startIndex = authHeader.indexOf(RESOURCE_METADATA_PARAMETER);
        if (startIndex == -1) {
            return Optional.empty();
        }

        startIndex += RESOURCE_METADATA_PARAMETER.length();

        startIndex = skipLeadingWhitespace(authHeader, startIndex);

        if (startIndex < authHeader.length() && authHeader.charAt(startIndex) == '"') {
            int endIndex = authHeader.indexOf("\"", startIndex + 1);

            if (endIndex <= startIndex + 1) {
                return Optional.empty();
            }

            String value = authHeader.substring(startIndex + 1, endIndex).trim();
            return value.isEmpty() ? Optional.empty() : Optional.of(value);
        }

        int endIndex = authHeader.indexOf(" ", startIndex);
        if (endIndex == -1) {
            endIndex = authHeader.length();
        }

        String metadataUrl = authHeader.substring(startIndex, endIndex).trim();
        return metadataUrl.isEmpty() ? Optional.empty() : Optional.of(metadataUrl);
    }

    private int skipLeadingWhitespace(String input, int index) {
        while (index < input.length() && Character.isWhitespace(input.charAt(index))) {
            index++;
        }
        return index;
    }

    public Map<String, String> convertHttpHeadersToMap(HttpHeaders httpHeaders) {
        return httpHeaders.map().entrySet()
                .stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> String.join(",", entry.getValue())
                ));
    }
}
