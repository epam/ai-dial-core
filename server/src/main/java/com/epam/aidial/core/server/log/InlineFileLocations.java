package com.epam.aidial.core.server.log;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Where each API carries files inline. A value is a file only at one of these locations, never because of its shape,
 * so encoding or reformatting text elsewhere cannot hide it from the analytics log.
 */
final class InlineFileLocations {

    private static final int MAX_DATA_URL_PREFIX_LENGTH = 256;
    private static final byte[] DATA_SCHEME = "data:".getBytes(StandardCharsets.US_ASCII);

    enum Condition {
        ALWAYS,
        DATA_URL,
        // the sibling "type" is "base64"
        BASE64_SOURCE,
        // a data URL, or the sibling "type" is a binary mime type
        BINARY_TYPE_OR_DATA_URL
    }

    /**
     * @param pattern     JSON Pointer segments; {@code *} matches any one segment.
     * @param mimeSibling the sibling member the mime type is read from, null when there is none.
     * @param mimePrefix  prepended to the sibling value, e.g. {@code audio/} for {@code input_audio.format}.
     */
    record Location(List<String> pattern, Condition condition, @Nullable String mimeSibling, @Nullable String mimePrefix) {

        boolean matches(List<String> path) {
            if (path.size() != pattern.size()) {
                return false;
            }
            for (int i = 0; i < path.size(); i++) {
                String segment = pattern.get(i);
                if (!"*".equals(segment) && !segment.equals(path.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * @param names the last segment of every location, so most strings are rejected by their member name alone.
     */
    record LocationSet(List<Location> locations, Set<String> names) {

        static final LocationSet EMPTY = of();

        static LocationSet of(Location... locations) {
            List<Location> list = List.of(locations);
            Set<String> names = list.stream()
                    .map(location -> location.pattern().get(location.pattern().size() - 1))
                    .collect(Collectors.toUnmodifiableSet());
            return new LocationSet(list, names);
        }

        boolean isEmpty() {
            return locations.isEmpty();
        }

        @Nullable
        Location find(List<String> path) {
            for (Location location : locations) {
                if (location.matches(path)) {
                    return location;
                }
            }
            return null;
        }
    }

    /**
     * @param payloadStart the first byte after the prefix's comma.
     * @param mime         null when the data URL names none.
     */
    record DataUrl(int payloadStart, @Nullable String mime) {
    }

    private static final LocationSet CHAT_COMPLETIONS = LocationSet.of(
            location("/messages/*/content/*/image_url/url", Condition.DATA_URL, null, null),
            location("/messages/*/content/*/input_audio/data", Condition.ALWAYS, "format", "audio/"),
            location("/messages/*/content/*/file/file_data", Condition.DATA_URL, null, null),
            location("/messages/*/custom_content/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/messages/*/custom_content/stages/*/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/message/audio/data", Condition.ALWAYS, null, null),
            location("/choices/*/message/custom_content/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/message/custom_content/stages/*/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/delta/audio/data", Condition.ALWAYS, null, null),
            location("/choices/*/delta/custom_content/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null),
            location("/choices/*/delta/custom_content/stages/*/attachments/*/data", Condition.BINARY_TYPE_OR_DATA_URL, "type", null));

    private static final LocationSet RESPONSES = LocationSet.of(
            location("/input/*/content/*/image_url", Condition.DATA_URL, null, null),
            location("/input/*/content/*/file_data", Condition.DATA_URL, null, null),
            location("/input/*/output/image_url", Condition.DATA_URL, null, null),
            location("/input/*/output/*/image_url", Condition.DATA_URL, null, null),
            location("/input/*/output/*/file_data", Condition.DATA_URL, null, null),
            location("/input/*/result", Condition.ALWAYS, null, null),
            location("/output/*/result", Condition.ALWAYS, null, null),
            location("/item/result", Condition.ALWAYS, null, null),
            location("/response/output/*/result", Condition.ALWAYS, null, null),
            location("/partial_image_b64", Condition.ALWAYS, null, null));

    private static final LocationSet ANTHROPIC_MESSAGES = LocationSet.of(
            location("/messages/*/content/*/source/data", Condition.BASE64_SOURCE, "media_type", null),
            // tool_result
            location("/messages/*/content/*/content/*/source/data", Condition.BASE64_SOURCE, "media_type", null),
            // web_fetch_tool_result
            location("/messages/*/content/*/content/content/source/data", Condition.BASE64_SOURCE, "media_type", null));

    private InlineFileLocations() {
    }

    static LocationSet forInterface(@Nullable InterfaceType type) {
        if (type == null) {
            return LocationSet.EMPTY;
        }
        return switch (type) {
            case OPENAI_CHAT_COMPLETIONS -> CHAT_COMPLETIONS;
            case OPENAI_RESPONSES -> RESPONSES;
            case ANTHROPIC_MESSAGES -> ANTHROPIC_MESSAGES;
            case OPENAI_EMBEDDINGS -> LocationSet.EMPTY;
        };
    }

    static boolean isBinaryType(@Nullable String type) {
        if (type == null) {
            return false;
        }
        String mime = type.toLowerCase(Locale.ROOT);
        int parameters = mime.indexOf(';');
        if (parameters >= 0) {
            mime = mime.substring(0, parameters);
        }
        mime = mime.trim();
        if (mime.equals("image/svg+xml")) {
            return false;
        }
        return mime.startsWith("image/") || mime.startsWith("audio/") || mime.startsWith("video/")
                || mime.equals("application/pdf") || mime.equals("application/octet-stream") || mime.equals("application/zip")
                || mime.equals("application/msword") || mime.startsWith("application/vnd.ms-")
                || mime.startsWith("application/vnd.openxmlformats-officedocument.");
    }

    /**
     * Reads a base64 data URL prefix ({@code data:<mime>[;param]*;base64,}) from the raw bytes of a JSON string.
     *
     * @param contentStart the first byte after the opening quote.
     * @param end          reading stops here: the closing quote, or the cut.
     * @return null when the value is not a base64 data URL, or its prefix does not end before {@code end}.
     */
    @Nullable
    static DataUrl parseDataUrl(byte[] body, int contentStart, int end) {
        int limit = Math.min(end, contentStart + MAX_DATA_URL_PREFIX_LENGTH);
        if (limit - contentStart < DATA_SCHEME.length) {
            return null;
        }
        for (int i = 0; i < DATA_SCHEME.length; i++) {
            if (toLowerAscii(body[contentStart + i]) != DATA_SCHEME[i]) {
                return null;
            }
        }
        for (int i = contentStart + DATA_SCHEME.length; i < limit; i++) {
            if (body[i] == '"') {
                return null;
            }
            if (body[i] == ',') {
                String prefix = new String(body, contentStart, i - contentStart, StandardCharsets.UTF_8);
                if (prefix.indexOf('\\') >= 0) {
                    prefix = unescape(prefix);
                }
                if (prefix == null || !prefix.toLowerCase(Locale.ROOT).endsWith(";base64")) {
                    return null;
                }
                String mime = prefix.substring(DATA_SCHEME.length, prefix.indexOf(';'));
                return new DataUrl(i + 1, mime.isEmpty() ? null : mime);
            }
        }
        return null;
    }

    private static Location location(String pointer, Condition condition, @Nullable String mimeSibling, @Nullable String mimePrefix) {
        return new Location(Arrays.asList(pointer.substring(1).split("/")), condition, mimeSibling, mimePrefix);
    }

    private static byte toLowerAscii(byte b) {
        return b >= 'A' && b <= 'Z' ? (byte) (b + ('a' - 'A')) : b;
    }

    @Nullable
    private static String unescape(String raw) {
        try {
            return ProxyUtil.MAPPER.readValue("\"" + raw + "\"", String.class);
        } catch (JsonProcessingException e) {
            // not a valid JSON string, so not a data URL either
            return null;
        }
    }
}
