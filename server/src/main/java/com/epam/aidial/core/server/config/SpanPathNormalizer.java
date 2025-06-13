package com.epam.aidial.core.server.config;

import com.epam.aidial.core.server.data.RouteTemplate;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;

/**
 * SpanProcessor that normalizes HTTP paths using RouteTemplate definitions.
 * This ensures consistency between controller routing and metrics normalization.
 */
public class SpanPathNormalizer implements SpanProcessor {

    private static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
    private static final AttributeKey<String> HTTP_ROUTE = AttributeKey.stringKey("http.route");

    @Override
    public void onStart(Context parentContext, ReadWriteSpan span) {
        if (SpanKind.SERVER != span.getKind()) {
            return;
        }

        var originalPath = span.getAttribute(URL_PATH);
        var normalizedPath = normalizePath(originalPath);

        span.setAttribute(HTTP_ROUTE, normalizedPath);
    }

    @Override
    public boolean isStartRequired() {
        return true;
    }

    @Override
    public void onEnd(ReadableSpan span) {
    }

    @Override
    public boolean isEndRequired() {
        return true;
    }

    @Override
    public CompletableResultCode shutdown() {
        return SpanProcessor.super.shutdown();
    }

    @Override
    public CompletableResultCode forceFlush() {
        return SpanProcessor.super.forceFlush();
    }

    @Override
    public void close() {
        SpanProcessor.super.close();
    }

    /**
     * Normalizes HTTP paths using RouteTemplate definitions
     */
    static String normalizePath(String path) {
        if (path == null) {
            return null;
        }
        for (RouteTemplate template : RouteTemplate.values()) {
            if (template.matches(path)) {
                return template.getNormalizedPath();
            }
        }
        return path;
    }
}
