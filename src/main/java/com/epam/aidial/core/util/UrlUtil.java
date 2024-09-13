package com.epam.aidial.core.util;

import com.google.common.escape.Escaper;
import com.google.common.net.UrlEscapers;
import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.net.PercentCodec;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;
import java.util.regex.Pattern;

@UtilityClass
public class UrlUtil {

    private static final PercentCodec DECODER = new PercentCodec();
    private static final Escaper ENCODER = UrlEscapers.urlPathSegmentEscaper();

    /**
     * Universal, non case-sensitive, protocol-agnostic URL pattern
     */
    private static final Pattern ABSOLUTE_URL_PATTERN = Pattern.compile("^(?:[a-z][a-z0-9-+.]*:)?//", Pattern.CASE_INSENSITIVE);

    @SneakyThrows
    public String encodePath(String path) {
        return ENCODER.escape(path);
    }

    public String decodePath(String path) {
        return decodePath(path, true);
    }

    @SneakyThrows
    public String decodePath(String path, boolean checkUri) {
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

    public boolean isAbsoluteUrl(String url) {
        return ABSOLUTE_URL_PATTERN.matcher(url).find();
    }
}
