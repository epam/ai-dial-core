package com.epam.aidial.core.util;

import lombok.SneakyThrows;
import lombok.experimental.UtilityClass;
import org.apache.commons.codec.net.PercentCodec;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.Charset;

@UtilityClass
public class UrlUtil {

    private static final PercentCodec CODEC = new PercentCodec(" ?/#".getBytes(Charset.defaultCharset()), false);

    @SneakyThrows
    public String encodePath(String path) {
        return new String(CODEC.encode(path.getBytes(Charset.defaultCharset())));
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
        return new String(CODEC.decode(path.getBytes(Charset.defaultCharset())));
    }
}
