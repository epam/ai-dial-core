package com.epam.aidial.core.util;

import io.opencensus.contrib.http.util.HttpTraceAttributeConstants;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.ReadableSpan;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.regex.Pattern;

@UtilityClass
public class SpanUtil {

    private static final AttributeKey<String> ATTR_HTTP_METHOD = AttributeKey.stringKey(HttpTraceAttributeConstants.HTTP_METHOD);
    private static final List<String> GROUPS = List.of("id", "bucket", "path");

    public static void updateName(Pattern pathPattern, String path) {
        String spanName = RegexUtil.replaceNamedGroups(pathPattern, path, GROUPS);
        if (Span.current() instanceof ReadableSpan span) {
            String method = span.getAttribute(ATTR_HTTP_METHOD);
            if (method != null) {
                spanName = method + " " + spanName;
            }
        }
        Span.current().updateName(spanName);
    }
}
