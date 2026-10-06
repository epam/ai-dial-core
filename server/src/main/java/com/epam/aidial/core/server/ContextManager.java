package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

/**
 * Keeps the ProxyContext of a request in that request's own Vert.x context for {@code AutoEnrichedOtelJsonLayout}.
 * Vert.x gives every HTTP server request a duplicated context and runs the request's upstream callbacks on it, so
 * the entry is visible to all of them, invisible to other requests, and collected with the request. There is no
 * clear step on purpose: clearing on response close dropped the entry while late callbacks still had to log.
 */
@Slf4j
@UtilityClass
public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

    /**
     * Stores the entry in the request's own context regardless of the current context.
     */
    public static void setProxyContext(ProxyContext proxyContext) {
        if (proxyContext == null) {
            return;
        }
        if (proxyContext.getRequest() instanceof HttpServerRequestInternal request) {
            request.context().putLocal(PROXY_CONTEXT_KEY, proxyContext);
        } else {
            // only mocks get here; a real request without a context would leave every log line unenriched
            log.warn("Request {} has no Vert.x context, log lines of this request carry no trace id", proxyContext.getRequest());
        }
    }

    public static ProxyContext getProxyContext() {
        Context vertxContext = Vertx.currentContext();
        return vertxContext == null ? null : vertxContext.getLocal(PROXY_CONTEXT_KEY);
    }
}
