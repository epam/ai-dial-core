package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

    /**
     * Set ProxyContext in Vertx context only.
     * This simplifies context management by storing the entire ProxyContext object
     * instead of managing individual attributes.
     * MDC is not used here to avoid duplication, as it will be temporarily enriched
     * during logging in AutoEnrichedOtelJsonLayout.
     */
    public static void setProxyContext(ProxyContext proxyContext) {
        if (proxyContext == null) {
            return;
        }

        // Store the entire ProxyContext in Vertx context only
        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            vertxContext.putLocal(PROXY_CONTEXT_KEY, proxyContext);

            // Also store individual fields for easier access by logging
            if (proxyContext.getProject() != null) {
                vertxContext.putLocal("user.project", proxyContext.getProject());
            }
            if (proxyContext.getUserSub() != null) {
                vertxContext.putLocal("user.sub", proxyContext.getUserSub());
            }
            if (proxyContext.getRequest() != null) {
                vertxContext.putLocal("request.uri", proxyContext.getRequest().uri());
                vertxContext.putLocal("request.method", proxyContext.getRequest().method().name());
            }
            // Store trace context fields
            if (proxyContext.getTraceId() != null) {
                vertxContext.putLocal("trace.id", proxyContext.getTraceId());
            }
            if (proxyContext.getSpanId() != null) {
                vertxContext.putLocal("span.id", proxyContext.getSpanId());
            }
        }
    }

    /**
     * Get ProxyContext from Vertx context.
     */
    public static ProxyContext getProxyContext() {
        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            return vertxContext.getLocal(PROXY_CONTEXT_KEY);
        }
        return null;
    }

    /**
     * Clear context data from Vertx context.
     * MDC is not cleared here as it's only temporarily enriched during logging.
     */
    public static void clearContext() {
        // Clear from Vertx context
        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            vertxContext.removeLocal(PROXY_CONTEXT_KEY);
            vertxContext.removeLocal("user.project");
            vertxContext.removeLocal("user.sub");
            vertxContext.removeLocal("request.uri");
            vertxContext.removeLocal("request.method");
            vertxContext.removeLocal("http.status.code");
            vertxContext.removeLocal("trace.id");
            vertxContext.removeLocal("span.id");
        }
    }
}