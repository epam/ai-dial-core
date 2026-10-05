package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import lombok.experimental.UtilityClass;

/**
 * Keeps the ProxyContext of a request in that request's own Vert.x context, where {@code AutoEnrichedOtelJsonLayout}
 * reads it to enrich the request's log lines. Vert.x gives every HTTP server request a duplicated context and runs
 * the request's upstream callbacks on it, so the entry is visible to all of them, including the ones that run after
 * a client disconnect, is invisible to other requests, and is collected with the request. There is no clear step on
 * purpose: clearing on response close dropped the entry while late callbacks still had to log. Futures shared between
 * requests must be continued on the waiter's own context ({@code FutureUtil.continueOnCallerContext}), or the waiter's
 * callbacks would run on, and read the entry of, another request.
 */
@UtilityClass
public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

    /**
     * Stores into the request's own context whatever context is current; a request without one (tests) stores nothing.
     */
    public static void setProxyContext(ProxyContext proxyContext) {
        if (proxyContext != null && proxyContext.getRequest() instanceof HttpServerRequestInternal request) {
            request.context().putLocal(PROXY_CONTEXT_KEY, proxyContext);
        }
    }

    public static ProxyContext getProxyContext() {
        Context vertxContext = Vertx.currentContext();
        return vertxContext == null ? null : vertxContext.getLocal(PROXY_CONTEXT_KEY);
    }
}
