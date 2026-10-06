package com.epam.aidial.core.server;

import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.impl.ContextInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Why {@link ContextManager} has no clear step: the entry lives in the request's own duplicated Vert.x context,
 * which is invisible to other requests. That it is collected with the request is checked end to end in
 * {@code DeploymentPostApiTest.testProxyContextIsReleasedAfterRequest_WhileConnectionsStayAlive}.
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
        ProxyContext firstProxyContext = proxyContextOf(first);
        ProxyContext secondProxyContext = proxyContextOf(second);

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
    void proxyContextIsStoredOnItsRequestContextWhateverContextIsCurrent() throws Exception {
        // a continuation that hopped onto another request's context, or onto the shared event-loop context,
        // must still file the entry under its own request
        ContextInternal own = requestContext();
        ContextInternal other = requestContext();
        ProxyContext proxyContext = proxyContextOf(own);

        on(other, () -> set(proxyContext));
        on(own.unwrap(), () -> set(proxyContext));

        assertSame(proxyContext, on(own, ContextManager::getProxyContext));
        assertNull(on(other, ContextManager::getProxyContext));
        assertNull(on(own.unwrap(), ContextManager::getProxyContext));
    }

    @Test
    void requestWithoutVertxContextStoresNothing() throws Exception {
        ProxyContext proxyContext = mock(ProxyContext.class);
        when(proxyContext.getRequest()).thenReturn(mock(HttpServerRequest.class));

        ContextInternal current = requestContext();

        on(current, () -> set(proxyContext));

        // in particular it must not fall back to the current context
        assertNull(on(current, ContextManager::getProxyContext));
    }

    /**
     * What Vert.x hands every HTTP server request: a duplicate of the event-loop context
     * (see {@code HttpServerImpl}: {@code streamContextSupplier = context::duplicate}).
     */
    private ContextInternal requestContext() {
        return ((ContextInternal) vertx.getOrCreateContext()).duplicate();
    }

    private static ProxyContext proxyContextOf(ContextInternal requestContext) {
        HttpServerRequestInternal request = mock(HttpServerRequestInternal.class);
        when(request.context()).thenReturn(requestContext);
        ProxyContext proxyContext = mock(ProxyContext.class);
        when(proxyContext.getRequest()).thenReturn(request);
        return proxyContext;
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
