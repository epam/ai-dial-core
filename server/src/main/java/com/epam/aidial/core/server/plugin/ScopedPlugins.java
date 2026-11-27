package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.Plugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.util.List;
import java.util.function.Supplier;

/**
 * Decorators that set the thread context class loader to a plugin directory's loader for each synchronous call Core
 * makes into the plugin's code, and restore the previous loader on return. The registry wraps a directory's
 * providers once; everything a provider hands back (the plugin, its responses, their body streams) is wrapped in
 * turn, so the rest of Core calls plugin code without knowing about class loaders.
 */
final class ScopedPlugins {

    private ScopedPlugins() {
    }

    private static <T> T call(ClassLoader loader, Supplier<T> call) {
        try (var _ = PluginClassLoaderScope.enter(loader)) {
            return call.get();
        }
    }

    private static void run(ClassLoader loader, Runnable call) {
        try (var _ = PluginClassLoaderScope.enter(loader)) {
            call.run();
        }
    }

    record ScopedProvider(PluginProvider delegate, ClassLoader loader) implements PluginProvider {

        @Override
        public String type() {
            return call(loader, delegate::type);
        }

        @Override
        public List<Problem> validate(ObjectNode settings) {
            return call(loader, () -> delegate.validate(settings));
        }

        @Override
        public Plugin create(ObjectNode settings, Vertx vertx) {
            Plugin plugin = call(loader, () -> delegate.create(settings, vertx));
            // null stays null: PluginManager reports a provider that returned none
            return plugin == null ? null : new ScopedPlugin(plugin, loader);
        }
    }

    record ScopedPlugin(Plugin delegate, ClassLoader loader) implements Plugin {

        @Override
        public Future<Response> handle(Request request, HostDelegate host) {
            Future<Response> answer = call(loader, () -> delegate.handle(request, host));
            // null stays null, and so does a null response: the embedded request reports both
            return answer == null ? null : answer.map(response -> response == null ? null : new ScopedResponse(response, loader));
        }

        @Override
        public void close() {
            run(loader, delegate::close);
        }
    }

    record ScopedResponse(Response delegate, ClassLoader loader) implements Response {

        @Override
        public int statusCode() {
            return call(loader, delegate::statusCode);
        }

        @Override
        public MultiMap headers() {
            return call(loader, delegate::headers);
        }

        @Override
        public ReadStream<Buffer> body() {
            ReadStream<Buffer> body = call(loader, delegate::body);
            return body == null ? null : new ScopedReadStream(body, loader);
        }

        @Override
        public void cancel() {
            run(loader, delegate::cancel);
        }
    }

    record ScopedReadStream(ReadStream<Buffer> delegate, ClassLoader loader) implements ReadStream<Buffer> {

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            run(loader, () -> delegate.exceptionHandler(handler));
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            run(loader, () -> delegate.handler(handler));
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            run(loader, delegate::pause);
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            run(loader, delegate::resume);
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            run(loader, () -> delegate.fetch(amount));
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            run(loader, () -> delegate.endHandler(endHandler));
            return this;
        }
    }
}
