package com.epam.aidial.core.server.util;

import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Static {@code proxy} settings.
 *
 * @param additionalHopByHopHeaders extra header names to strip on top of the built-in hop-by-hop list; never null.
 */
public record ProxySettings(List<String> additionalHopByHopHeaders) {

    public ProxySettings {
        additionalHopByHopHeaders = additionalHopByHopHeaders == null ? List.of() : List.copyOf(additionalHopByHopHeaders);
    }

    public static ProxySettings from(JsonObject settings) {
        return new ProxySettings(parseHeaders(settings.getJsonArray("additionalHopByHopHeaders")));
    }

    private static List<String> parseHeaders(JsonArray value) {
        List<String> headers = new ArrayList<>();
        if (value == null) {
            return headers;
        }
        for (Object item : value) {
            if (item instanceof String s && !s.isBlank()) {
                headers.add(s.trim());
            }
        }
        return headers;
    }
}
