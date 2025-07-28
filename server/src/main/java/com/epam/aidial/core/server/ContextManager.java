package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;

public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

    /**
     * Set ProxyContext in Vertx context only.
     * This simplifies context management by storing the entire ProxyContext object.
     * The AutoEnrichedOtelJsonLayout will extract fields directly from ProxyContext.
     * MDC is not used here to avoid duplication, as it will be temporarily enriched
     * during logging in AutoEnrichedOtelJsonLayout.
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
        // Clear ProxyContext from Vertx context
        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            vertxContext.removeLocal(PROXY_CONTEXT_KEY);
        }
    }

    /**
     * Get a value from Vertx context by key.
     *
     * @param key the key to look up
     * @return the value as a String, or null if not found
     */
    public static String getContextValue(String key) {
        try {
            Context vertxContext = Vertx.currentContext();
            if (vertxContext != null) {
                Object localValue = vertxContext.getLocal(key);
                if (localValue != null) {
                    return localValue.toString();
                }
            }
        } catch (Exception e) {
            // Ignore
        }
        return null;
    }
}