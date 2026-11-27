package com.epam.aidial.core.server.plugin;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PluginClassLoaderScopeTest {

    @Test
    void testNestedScopesRestorePreviousLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        ClassLoader outer = new ClassLoader(previous) { };
        ClassLoader inner = new ClassLoader(previous) { };
        try (var ignored = PluginClassLoaderScope.enter(outer)) {
            assertSame(outer, Thread.currentThread().getContextClassLoader());
            try (var nested = PluginClassLoaderScope.enter(inner)) {
                assertSame(inner, Thread.currentThread().getContextClassLoader());
            }
            assertSame(outer, Thread.currentThread().getContextClassLoader());
        }
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void testExceptionRestoresLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        assertThrows(NoClassDefFoundError.class, () -> {
            try (var ignored = PluginClassLoaderScope.enter(new ClassLoader(previous) { })) {
                throw new NoClassDefFoundError("private dependency");
            }
        });
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }

    @Test
    void testPendingFutureDoesNotRetainLoader() {
        ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Future<Void> future;
        try (var ignored = PluginClassLoaderScope.enter(new ClassLoader(previous) { })) {
            future = Promise.<Void>promise().future();
        }
        assertFalse(future.isComplete());
        assertSame(previous, Thread.currentThread().getContextClassLoader());
    }
}
