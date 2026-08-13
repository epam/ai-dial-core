package com.epam.aidial.core.storage.util;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.net.PercentCodec;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@UtilityClass
public class UrlUtil {

    private static final PercentCodec DECODER = new PercentCodec();
    private static final Escaper ENCODER = UrlEscapers.urlPathSegmentEscaper();

    /**
     * Universal, non case-sensitive, protocol-agnostic URL pattern
     */
    private static final Pattern ABSOLUTE_URL_PATTERN = Pattern.compile("^[a-z][a-z0-9-+.]*?://", Pattern.CASE_INSENSITIVE);

    @SneakyThrows
    public String encodePathSegment(@Nullable String segment) {
        if (segment == null) {
            return null;
        }
        return ENCODER.escape(segment);
    }

    /**
     * Escapes a JSON Schema {@code $id} so it can be stored as a flat blob-storage key: replaces
     * {@code %} with {@code %25} first, then {@code /} with {@code %2F}. The result contains no
     * literal {@code /} characters while preserving all other URI characters (e.g. {@code :}).
     * Inverse: {@link #unescapeSchemaId}.
     */
    public static String escapeSchemaId(String schemaId) {
        return schemaId.replace("%", "%25").replace("/", "%2F");
    }

    /**
     * Reverses {@link #escapeSchemaId}: replaces {@code %2F} with {@code /} first, then
     * {@code %25} with {@code %}. The reversed order ensures a literal {@code %2F} in the
     * original value (stored as {@code %252F}) is restored correctly.
     */
    public static String unescapeSchemaId(String escaped) {
        return escaped.replace("%2F", "/").replace("%25", "%");
    }

    public String encodePath(@Nullable String path) {
        if (path == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();

        for (int i = 0; i < path.length(); i++) {
            int index = path.indexOf('/', i);
            if (index == -1) {
                builder.append(encodePathSegment(path.substring(i)));
                break;
            }

            builder.append(encodePathSegment(path.substring(i, index)));
            builder.append("/");
            i = index;
        }

        return builder.toString();
    }

    public String decodePath(@Nullable String path) {
        return decodePath(path, true);
    }

    @SneakyThrows
    public String decodePath(@Nullable String path, boolean checkUri) {
        if (path == null) {
            return null;
        }
        if (checkUri) {
            try {
                URI uri = new URI(path);
                if (uri.getRawFragment() != null || uri.getRawQuery() != null) {
                    throw new IllegalArgumentException("Wrong path provided " + path);
                }
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }
        return new String(DECODER.decode(path.getBytes(Charset.defaultCharset())));
    }

    public String tryDecodePath(@Nullable String path) {
        try {
            return decodePath(path, false);
        } catch (Exception e) {
            return path; // Return original path if decoding fails
        }
    }

    public boolean isAbsoluteUrl(@Nullable String url) {
        return url != null && ABSOLUTE_URL_PATTERN.matcher(url).find();
    }

    public boolean isDataUrl(@Nullable String url) {
        return url != null && url.startsWith("data:");
    }

    public boolean isFolder(@Nullable String url) {
        return url != null && url.endsWith(ResourceDescriptor.PATH_SEPARATOR);
    }
}
