package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * Why {@link ContextManager} has no clear step: the entry lives in the request's own duplicated Vert.x context,
 * which is invisible to other requests and is collected with the request.
 */
class ContextManagerTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void proxyContextIsVisibleOnlyToItsOwnRequestContext() throws Exception {
        // two requests on the same event loop, e.g. sequential requests over one keep-alive connection
        ContextInternal first = requestContext();
        ContextInternal second = requestContext();
        ProxyContext firstProxyContext = mock(ProxyContext.class);
        ProxyContext secondProxyContext = mock(ProxyContext.class);

        on(first, () -> set(firstProxyContext));

        // nothing bleeds into the next request although nothing was cleared
        assertNull(on(second, ContextManager::getProxyContext));
        assertNull(on(first.unwrap(), ContextManager::getProxyContext));

        on(second, () -> set(secondProxyContext));

        // and the first request keeps its own entry for its late callbacks
        assertSame(firstProxyContext, on(first, ContextManager::getProxyContext));
        assertSame(secondProxyContext, on(second, ContextManager::getProxyContext));
    }

    @Test
    void proxyContextIsCollectedWithItsRequestContext() throws Exception {
        WeakReference<ProxyContext> stored = storeOnThrowawayRequestContext();

        long deadline = System.currentTimeMillis() + 10_000;
        while (stored.get() != null) {
            if (System.currentTimeMillis() > deadline) {
                fail("ProxyContext is still reachable after its request context was dropped");
            }
            System.gc();
            Thread.sleep(50);
        }
    }

    private WeakReference<ProxyContext> storeOnThrowawayRequestContext() throws Exception {
        ProxyContext proxyContext = mock(ProxyContext.class);
        on(requestContext(), () -> set(proxyContext));
        return new WeakReference<>(proxyContext);
    }

    /**
     * What Vert.x hands every HTTP server request: a duplicate of the event-loop context
     * (see {@code HttpServerImpl}: {@code streamContextSupplier = context::duplicate}).
     */
    private ContextInternal requestContext() {
        return ((ContextInternal) vertx.getOrCreateContext()).duplicate();
    }

    private static Void set(ProxyContext proxyContext) {
        ContextManager.setProxyContext(proxyContext);
        return null;
    }

    private static <T> T on(Context context, Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        context.runOnContext(v -> {
            try {
                result.complete(action.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(10, TimeUnit.SECONDS);
    }
}
