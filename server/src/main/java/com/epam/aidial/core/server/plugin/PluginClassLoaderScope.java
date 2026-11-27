package com.epam.aidial.core.server.plugin;

/** Restores the calling thread's loader on synchronous return, including nested plugin calls. */
public final class PluginClassLoaderScope implements AutoCloseable {

    private final Thread thread;
    private final ClassLoader previous;

    private PluginClassLoaderScope(ClassLoader loader) {
        thread = Thread.currentThread();
        previous = thread.getContextClassLoader();
        thread.setContextClassLoader(loader);
    }

    public static PluginClassLoaderScope enter(ClassLoader loader) {
        return new PluginClassLoaderScope(loader);
    }

    @Override
    public void close() {
        thread.setContextClassLoader(previous);
    }
}
