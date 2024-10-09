package com.epam.aidial.core.util;

import io.opentelemetry.api.trace.Span;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.regex.Pattern;

@UtilityClass
public class SpanUtil {

    private static final List<String> GROUPS = List.of("id", "bucket", "path");

    public static void updateName(Pattern pathPattern, String path, String httpMethod) {
        String spanName = RegexUtil.replaceNamedGroups(pathPattern, path, GROUPS);
        Span.current().updateName(httpMethod + " " + spanName);
    }
}
