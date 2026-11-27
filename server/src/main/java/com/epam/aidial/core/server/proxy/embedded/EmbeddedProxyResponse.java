package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.core.server.plugin.PluginClassLoaderScope;
import com.epam.aidial.core.server.proxy.ProxyResponse;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * A plugin's answer as a {@link ProxyResponse}: the stream methods go to the plugin's body stream.
 */
final class EmbeddedProxyResponse implements ProxyResponse {

    private final Response answer;
    private final ReadStream<Buffer> body;
    private final ClassLoader pluginClassLoader;
    private final Runnable onDiscard;
    private final Runnable onBodyRead;
    private boolean cancelled;

    /**
     * @param onDiscard what else ends with the answer: the in-process calls of the attempt
     */
    EmbeddedProxyResponse(Response answer, Runnable onDiscard) {
        this(answer, onDiscard, () -> {
        });
    }

    /**
     * @param onBodyRead runs once {@link #body()} has read the whole body or failed
     * @throws IllegalStateException when the answer has no headers or no body
     */
    EmbeddedProxyResponse(Response answer, Runnable onDiscard, Runnable onBodyRead) {
        this(answer, onDiscard, onBodyRead, answer.getClass().getClassLoader());
    }

    EmbeddedProxyResponse(Response answer, Runnable onDiscard, Runnable onBodyRead, ClassLoader pluginClassLoader) {
        this.pluginClassLoader = pluginClassLoader;
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            if (answer.headers() == null) {
                throw new IllegalStateException("The plugin answered without headers");
            }
            this.answer = answer;
            this.body = answer.body();
        }
        if (body == null) {
            throw new IllegalStateException("The plugin answered without a body");
        }
        this.onDiscard = onDiscard;
        this.onBodyRead = onBodyRead;
    }

    @Override
    public int statusCode() {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            return answer.statusCode();
        }
    }

    @Override
    public MultiMap headers() {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            return answer.headers();
        }
    }

    @Override
    public String getHeader(CharSequence name) {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            return answer.headers().get(name);
        }
    }

    @Override
    public Future<Buffer> body() {
        Promise<Buffer> promise = Promise.promise();
        Buffer content = Buffer.buffer();
        exceptionHandler(promise::tryFail);
        endHandler(ignored -> promise.tryComplete(content));
        handler(content::appendBuffer);
        resume();
        return promise.future().onComplete(ignored -> onBodyRead.run());
    }

    @Override
    public void discard() {
        if (!cancelled) {
            cancelled = true;
            try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
                answer.cancel();
            }
            onDiscard.run();
        }
    }

    @Override
    public EmbeddedProxyResponse exceptionHandler(Handler<Throwable> handler) {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            body.exceptionHandler(handler);
        }
        return this;
    }

    @Override
    public EmbeddedProxyResponse handler(Handler<Buffer> handler) {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            body.handler(handler);
        }
        return this;
    }

    @Override
    public EmbeddedProxyResponse pause() {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            body.pause();
        }
        return this;
    }

    @Override
    public EmbeddedProxyResponse resume() {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            body.resume();
        }
        return this;
    }

    @Override
    public EmbeddedProxyResponse fetch(long amount) {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            body.fetch(amount);
        }
        return this;
    }

    @Override
    public EmbeddedProxyResponse endHandler(Handler<Void> endHandler) {
        try (var ignored = PluginClassLoaderScope.enter(pluginClassLoader)) {
            body.endHandler(endHandler);
        }
        return this;
    }
}
