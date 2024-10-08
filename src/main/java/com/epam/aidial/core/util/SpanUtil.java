package com.epam.aidial.core.util;

import io.opencensus.contrib.http.util.HttpTraceAttributeConstants;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.ReadableSpan;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.regex.Matcher;

@UtilityClass
public class SpanUtil {

    private static final AttributeKey<String> ATTR_HTTP_METHOD = AttributeKey.stringKey(HttpTraceAttributeConstants.HTTP_METHOD);
    private static final List<String> GROUPS = List.of("id", "bucket", "path");

    public void updateName(String path, Matcher matcher) {
        String spanName = path;
        if (Span.current() instanceof ReadableSpan span) {
            String method = span.getAttribute(ATTR_HTTP_METHOD);
            if (method != null) {
                spanName = method + " " + spanName;
            }
        }
        if (matcher.groupCount() > 0) {
            for (String group : GROUPS) {
                spanName = replace(spanName, group, matcher);
            }
        }
        Span.current().updateName(spanName);
    }

    private String replace(String spanName, String group, Matcher matcher) {
        try {
            return spanName.replaceAll(matcher.group(group), "{%s}".formatted(group));
        } catch (IllegalArgumentException ignored) {
            return spanName;
        }
    }
}
