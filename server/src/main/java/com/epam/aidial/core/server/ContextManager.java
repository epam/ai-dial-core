package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import lombok.experimental.UtilityClass;

@UtilityClass
public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

    /**
     * Stores the ProxyContext in the current Vert.x context, where {@code AutoEnrichedOtelJsonLayout} reads it to
     * enrich every log line of the request.
     *
     * <p>Vert.x runs each HTTP server request on its own duplicated context, and the upstream calls made from it
     * dispatch their callbacks on that same context. So the entry is visible to every callback of the request,
     * including the ones that run after the client has disconnected, is never visible to another request, and is
     * garbage-collected together with the request. There is deliberately no clear step: clearing on response close
     * made the log lines of those late callbacks lose their trace id and user attributes.
     *
     * <p>A shared (non-duplicated) event-loop or worker context outlives every request, so an entry written there
     * would never go away and would enrich unrelated log lines; such writes are skipped.
     */
    public static void setProxyContext(ProxyContext proxyContext) {
        if (proxyContext == null) {
            return;
        }

        Context vertxContext = Vertx.currentContext();
        if (vertxContext instanceof ContextInternal internal && internal.isDuplicate()) {
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
}
