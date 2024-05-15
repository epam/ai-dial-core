package com.epam.aidial.core.util;

import lombok.experimental.UtilityClass;

import java.net.URI;
import java.net.URISyntaxException;

@UtilityClass
public class UrlUtil {

    public String encodePath(String path) {
        try {
            URI uri = new URI(null, null, path, null);
            return uri.toASCIIString();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }

    public String decodePath(String path) {
        try {
            URI uri = new URI(path);
            if (uri.getRawFragment() != null || uri.getRawQuery() != null) {
                throw new IllegalArgumentException("Wrong path provided " + path);
            }
            return uri.getPath();
        } catch (URISyntaxException e) {
            throw new RuntimeException(e);
        }
    }
}
