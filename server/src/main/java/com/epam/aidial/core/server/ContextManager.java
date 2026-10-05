package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import lombok.experimental.UtilityClass;

import java.lang.ref.WeakReference;

@UtilityClass
public class ContextManager {

    private static final String PROXY_CONTEXT_KEY = "proxyContext";

    /**
     * Stores a weak reference to the ProxyContext in the current Vert.x context, where
     * {@code AutoEnrichedOtelJsonLayout} reads it to enrich every log line of the request.
     *
     * <p>Vert.x runs each HTTP server request on its own duplicated context, and the upstream calls made from it
     * dispatch their callbacks on that same context. So the entry is visible to every callback of the request,
     * including the ones that run after the client has disconnected. There is deliberately no clear step: clearing
     * on response close made the log lines of those late callbacks lose their trace id and user attributes.
     *
     * <p>Three things keep this safe without a clear step. The entry is only ever stored into a duplicated context,
     * never into the shared event-loop or worker context, where it would outlive every request and enrich unrelated
     * log lines. The reference is weak, so a request context that something holds beyond the request (a cached
     * future created on it, for instance) cannot keep the ProxyContext and its request and response bodies alive:
     * the ProxyContext lives exactly as long as the request's own controller and stream handlers hold it, which is
     * as long as any of them can still log. And futures shared between requests are continued on each waiter's own
     * context (see {@code FutureUtil.continueOnCallerContext}), so a waiter never stores its ProxyContext into
     * another request's context.
     */
    public static void setProxyContext(ProxyContext proxyContext) {
        if (proxyContext == null) {
            return;
        }

        if (Vertx.currentContext() instanceof ContextInternal context && context.isDuplicate()) {
            context.putLocal(PROXY_CONTEXT_KEY, new WeakReference<>(proxyContext));
        }
    }

    /**
     * Get ProxyContext from Vertx context.
     */
    public static ProxyContext getProxyContext() {
        Context vertxContext = Vertx.currentContext();
        if (vertxContext == null) {
            return null;
        }
        WeakReference<ProxyContext> reference = vertxContext.getLocal(PROXY_CONTEXT_KEY);
        return reference == null ? null : reference.get();
    }
}
