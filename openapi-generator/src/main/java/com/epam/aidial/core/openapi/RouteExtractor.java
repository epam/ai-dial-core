package com.epam.aidial.core.openapi;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class RouteExtractor {

    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{([^}]+)}");

    // Sample values that match the regex alternation groups in RouteTemplate patterns
    private static final Map<String, String> SAMPLE_VALUES = Map.of(
            "action", "completions",
            "resourceType", "conversations",
            "operation", "list",
            "routePath", "/testroute"
    );

    private static final String DEFAULT_SAMPLE = "testvalue";

    private RouteExtractor() {
    }

    public static List<String> extractPathParams(String pathTemplate) {
        Matcher matcher = PATH_PARAM_PATTERN.matcher(pathTemplate);
        List<String> params = new ArrayList<>();
        while (matcher.find()) {
            params.add(matcher.group(1));
        }
        return Collections.unmodifiableList(params);
    }

    public static String toSamplePath(String pathTemplate) {
        Matcher matcher = PATH_PARAM_PATTERN.matcher(pathTemplate);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String paramName = matcher.group(1);
            String sampleValue = SAMPLE_VALUES.getOrDefault(paramName, DEFAULT_SAMPLE);
            matcher.appendReplacement(sb, sampleValue);
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
