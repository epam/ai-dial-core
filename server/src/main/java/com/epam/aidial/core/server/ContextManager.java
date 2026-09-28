package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

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
     * Detaches the ProxyContext (and its request/response bodies) from the current Vertx context.
     */
    public static void clearContext() {
        Context vertxContext = Vertx.currentContext();
        if (vertxContext != null) {
            vertxContext.removeLocal(PROXY_CONTEXT_KEY);
        }
    }
}
