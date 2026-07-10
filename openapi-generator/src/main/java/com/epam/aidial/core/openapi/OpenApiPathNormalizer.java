package com.epam.aidial.core.openapi;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes OpenAPI path templates for comparison and merge by lowercasing
 * only path parameter placeholders ({@code {param}}), not static segments.
 */
public final class OpenApiPathNormalizer {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");

    private OpenApiPathNormalizer() {
    }

    public static String normalizePath(String path) {
        if (path == null || path.isEmpty()) {
            return path;
        }
        Matcher matcher = PATH_PARAM_PATTERN.matcher(path);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            matcher.appendReplacement(sb, "{" + matcher.group(1).toLowerCase(Locale.ROOT) + "}");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}