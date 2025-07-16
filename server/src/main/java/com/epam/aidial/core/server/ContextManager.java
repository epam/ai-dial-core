package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import org.slf4j.MDC;

public class ContextManager {
    private static final String USER_PROJECT = "user.project";
    private static final String USER_SUB = "user.sub";
    private static final String TRACE_ID = "trace.id";
    private static final String SPAN_ID = "span.id";
    private static final String REQUEST_URI = "request.uri";
    private static final String REQUEST_METHOD = "request.method";

    public static void setContext(String project, String userSub, String traceId, String spanId) {
        if (project != null) {
            MDC.put(USER_PROJECT, project);
        }
        MDC.put(USER_SUB, userSub != null ? userSub : "unknown");

        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            if (project != null) {
                vertxContext.putLocal(USER_PROJECT, project);
            }
            vertxContext.putLocal(USER_SUB, userSub != null ? userSub : "unknown");
            if (traceId != null) {
                vertxContext.putLocal(TRACE_ID, traceId);
            }
            if (spanId != null) {
                vertxContext.putLocal(SPAN_ID, spanId);
            }
        }
    }

    public static void setRequestContext(HttpServerRequest request) {
        setContextValue(REQUEST_URI, request.uri());
        setContextValue(REQUEST_METHOD, request.method().name());
    }

    private static void setContextValue(String key, String value) {
        if (value != null) {
            MDC.put(key, value);

            Context vertxContext = Vertx.currentContext();
            if (vertxContext != null) {
                vertxContext.putLocal(key, value);
            }
        }
    }

    public static void clearContext() {
        MDC.clear();
    }
}
