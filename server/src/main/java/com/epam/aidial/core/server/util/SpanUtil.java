package com.epam.aidial.core.server.util;

import io.opentelemetry.api.trace.Span;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.regex.Pattern;

@UtilityClass
public class SpanUtil {

    private static final List<String> GROUPS = List.of("id", "bucket", "path");

    public static void updateName(Pattern pathPattern, String path, String httpMethod) {
        String httpPath = RegexUtil.replaceNamedGroups(pathPattern, path, GROUPS);
        updateName(httpMethod, httpPath);
    }

    public static void updateName(String httpMethod, String httpPath) {
        updateName(httpMethod + " " + httpPath);
    }

    public static void updateName(String name) {
        Span.current().updateName(name);
    }
}
