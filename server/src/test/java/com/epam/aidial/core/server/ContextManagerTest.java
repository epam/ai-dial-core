package com.epam.aidial.core.server;

import com.epam.aidial.core.server.log.LogContext;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ContextManagerTest {

    @Test
    void getLogAttributesReturnsTheLiveProxyContext() {
        Context vertxContext = mock(Context.class);
        ProxyContext proxyContext = mock(ProxyContext.class);
        when(vertxContext.getLocal("proxyContext")).thenReturn(proxyContext);
        try (MockedStatic<Vertx> vertx = mockStatic(Vertx.class)) {
            vertx.when(Vertx::currentContext).thenReturn(vertxContext);

            assertSame(proxyContext, ContextManager.getLogAttributes());
        }
    }

    @Test
    void getLogAttributesPrefersTheLiveProxyContextOverTheSnapshot() {
        Context vertxContext = mock(Context.class);
        ProxyContext proxyContext = mock(ProxyContext.class);
        LogContext snapshot = new LogContext("t", "s", "01", "p", "u", "GET", "/x", false, null, 0, null);
        when(vertxContext.getLocal("proxyContext")).thenReturn(proxyContext);
        when(vertxContext.getLocal("logContext")).thenReturn(snapshot);
        try (MockedStatic<Vertx> vertx = mockStatic(Vertx.class)) {
            vertx.when(Vertx::currentContext).thenReturn(vertxContext);

            assertSame(proxyContext, ContextManager.getLogAttributes());
        }
    }

    @Test
    void getLogAttributesFallsBackToTheSnapshot() {
        Context vertxContext = mock(Context.class);
        LogContext snapshot = new LogContext("t", "s", "01", "p", "u", "GET", "/x", false, null, 0, null);
        when(vertxContext.getLocal("logContext")).thenReturn(snapshot);
        try (MockedStatic<Vertx> vertx = mockStatic(Vertx.class)) {
            vertx.when(Vertx::currentContext).thenReturn(vertxContext);

            assertSame(snapshot, ContextManager.getLogAttributes());
        }
    }

    @Test
    void getLogAttributesIsNullWhenNothingIsStored() {
        Context vertxContext = mock(Context.class);
        try (MockedStatic<Vertx> vertx = mockStatic(Vertx.class)) {
            vertx.when(Vertx::currentContext).thenReturn(vertxContext);

            assertNull(ContextManager.getLogAttributes());
        }
    }

    @Test
    void clearContextSwapsProxyContextForSnapshot() {
        Context vertxContext = mock(Context.class);
        ProxyContext proxyContext = mock(ProxyContext.class);
        when(proxyContext.getProject()).thenReturn("project");
        when(vertxContext.getLocal("proxyContext")).thenReturn(proxyContext);
        try (MockedStatic<Vertx> vertx = mockStatic(Vertx.class)) {
            vertx.when(Vertx::currentContext).thenReturn(vertxContext);

            ContextManager.clearContext();

            verify(vertxContext).putLocal(eq("logContext"),
                    argThat(value -> value instanceof LogContext log && "project".equals(log.getProject())));
            verify(vertxContext).removeLocal("proxyContext");
        }
    }

    @Test
    void clearContextWithoutProxyContextStoresNothing() {
        Context vertxContext = mock(Context.class);
        try (MockedStatic<Vertx> vertx = mockStatic(Vertx.class)) {
            vertx.when(Vertx::currentContext).thenReturn(vertxContext);

            ContextManager.clearContext();

            verify(vertxContext, never()).putLocal(anyString(), any());
        }
    }

    @Test
    void worksWithoutVertxContext() {
        try (MockedStatic<Vertx> vertx = mockStatic(Vertx.class)) {
            vertx.when(Vertx::currentContext).thenReturn(null);

            ContextManager.setProxyContext(mock(ProxyContext.class));
            ContextManager.clearContext();

            assertNull(ContextManager.getLogAttributes());
        }
    }
}
