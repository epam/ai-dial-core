package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.LogAttributes;
import com.epam.aidial.core.server.log.LogContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";
    private static final String LOG_CONTEXT_KEY = "logContext";

    /**
     * Set ProxyContext in Vertx context only.
     * This simplifies context management by storing the entire ProxyContext object.
     * The AutoEnrichedOtelJsonLayout will extract fields directly from ProxyContext.
     */
    public static void setProxyContext(ProxyContext proxyContext) {
        if (proxyContext == null) {
            return;
        }

        // Store only the ProxyContext object in Vertx context
        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            vertxContext.putLocal(PROXY_CONTEXT_KEY, proxyContext);
        }
    }

    /**
     * Get what log records need from the Vertx context: the live ProxyContext while it is in place, the snapshot
     * left by {@link #clearContext()} after that, null when neither exists yet.
     */
    public static LogAttributes getLogAttributes() {
        Context vertxContext = Vertx.currentContext();
        if (vertxContext == null) {
            return null;
        }
        ProxyContext proxyContext = vertxContext.getLocal(PROXY_CONTEXT_KEY);
        if (proxyContext != null) {
            return proxyContext;
        }
        return vertxContext.getLocal(LOG_CONTEXT_KEY);
    }

    /**
     * Replace the ProxyContext in the Vertx context with a snapshot of what log records need, so the request and
     * response bodies it holds are not retained by the Vertx context. The request may outlive its connection and
     * log after this, so the snapshot must keep the log attributes.
     */
    public static void clearContext() {
        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            ProxyContext proxyContext = vertxContext.getLocal(PROXY_CONTEXT_KEY);
            if (proxyContext != null) {
                vertxContext.putLocal(LOG_CONTEXT_KEY, LogContext.of(proxyContext));
                vertxContext.removeLocal(PROXY_CONTEXT_KEY);
            }
        }
    }
}
