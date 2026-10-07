package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import lombok.experimental.UtilityClass;

/**
 * Keeps the ProxyContext of a request in that request's own Vert.x context for {@code AutoEnrichedOtelJsonLayout}.
 * Vert.x gives every HTTP server request a duplicated context and runs the request's upstream callbacks on it, so
 * the entry is visible to all of them, invisible to other requests, and collected with the request. There is no
 * clear step on purpose: clearing on response close dropped the entry while late callbacks still had to log.
 * {@code Proxy} stores the entry right after it has put the request back on its own context (see
 * {@code FutureUtil.continueOnCallerContext}), so the current context here is the request's own.
 */
@UtilityClass
public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

    public static void setProxyContext(ProxyContext proxyContext) {
        Context vertxContext = Vertx.currentContext();
        if (proxyContext != null && vertxContext != null) {
            vertxContext.putLocal(PROXY_CONTEXT_KEY, proxyContext);
        }
    }

    public static ProxyContext getProxyContext() {
        Context vertxContext = Vertx.currentContext();
        return vertxContext == null ? null : vertxContext.getLocal(PROXY_CONTEXT_KEY);
    }
}
