package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The cache and lifecycle of the origin plugin instances serving the {@code plugins} entries. An instance is created
 * from an entry's settings on the first request it serves and again on the first request after the entry changes. A
 * plugin replaced that way, or whose entry a request names after it was removed, is closed once no attempt holds it;
 * every plugin is closed by {@link #close}.
 */
@Slf4j
@RequiredArgsConstructor
public class PluginManager {

    // the users count of a closed instance
    private static final int CLOSED = Integer.MIN_VALUE;

    private final PluginRegistry pluginRegistry;
    private final Vertx vertx;
    private final AsyncTaskExecutor taskExecutor;
    private final Map<String, Origin> origins = new ConcurrentHashMap<>();
    // every instance created and not closed yet: a replaced one still in use is in no map
    private final Set<Instance> open = ConcurrentHashMap.newKeySet();
    private volatile boolean closed;

    /**
     * The plugin serving the entry {@code name} of {@code config}. Takes no lock, as it runs on the event loop: two
     * requests racing on a new declaration may each create a plugin, and the one replaced is closed.
     *
     * @throws IllegalStateException when no entry has that name, its provider is not loaded, creating the plugin fails
     *                               or makes one that serves no requests, or Core is shutting down
     */
    public OriginPlugin resolve(Config config, String name) {
        return instance(config, name).plugin;
    }

    /**
     * The plugin serving the entry {@code name} of {@code config}, held for one attempt: it is not closed until the
     * lease's {@code onDone} runs. A second run of {@code onDone} does nothing.
     *
     * @throws IllegalStateException as {@link #resolve} does, or when the plugin closed twice while the attempt started
     */
    public Lease acquire(Config config, String name) {
        Instance instance = instance(config, name);
        if (!instance.acquire()) {
            // it closed after the lookup, so the map holds its replacement now
            instance = instance(config, name);
            if (!instance.acquire()) {
                throw new IllegalStateException("Plugin '" + name + "' closed while a request started on it");
            }
        }
        Instance held = instance;
        AtomicBoolean done = new AtomicBoolean();
        // a count given back twice would close the instance under another attempt
        return new Lease(held.plugin, () -> {
            if (done.compareAndSet(false, true)) {
                held.release();
            }
        });
    }

    /**
     * Closes every plugin, those in use included, and fails every later {@link #resolve} and {@link #acquire}. The
     * future completes once the plugins have closed, those already closing included.
     */
    public Future<Void> close() {
        closed = true;
        List<Future<Void>> closes = open.stream().map(Instance::closeNow).toList();
        return Future.join(closes).mapEmpty();
    }

    private Instance instance(Config config, String name) {
        if (closed) {
            throw new IllegalStateException("Core is shutting down");
        }
        Plugin declaration = config.getPlugins().get(name);
        if (declaration == null) {
            Origin removed = origins.remove(name);
            if (removed != null) {
                removed.instance().retire();
            }
            throw new IllegalStateException("No plugins entry is named '" + name + "'");
        }
        Origin cached = origins.get(name);
        if (cached != null && cached.declaration().equals(declaration)) {
            if (cached.declaration() != declaration) {
                // a rebuild made a new object for the same declaration: keep it, so the next equals is an identity
                // check, unless another request has replaced the plugin meanwhile
                origins.replace(name, cached, new Origin(declaration, cached.instance()));
            }
            return cached.instance();
        }
        PluginProvider provider = pluginRegistry.get(declaration.getType());
        if (provider == null) {
            throw new IllegalStateException("No plugin provider of type '" + declaration.getType() + "' is loaded");
        }
        com.epam.aidial.core.plugin.Plugin created;
        try {
            created = provider.create(declaration.getSettings(), vertx);
        } catch (RuntimeException | LinkageError e) {
            // plugin code: a jar missing a class throws NoClassDefFoundError on first use
            throw new IllegalStateException("Plugin '" + name + "' cannot be created: " + e.getMessage(), e);
        }
        if (created == null) {
            throw new IllegalStateException("Plugin '" + name + "' cannot be created: its provider returned null");
        }
        if (!(created instanceof OriginPlugin plugin)) {
            closePlugin(name, created);
            throw new IllegalStateException("The plugin of type '" + declaration.getType() + "' serves no requests");
        }
        Instance instance = new Instance(name, plugin);
        open.add(instance);
        if (closed) {
            // close() may have walked the set before the add
            instance.closeNow();
            throw new IllegalStateException("Core is shutting down");
        }
        Origin replaced = origins.put(name, new Origin(declaration, instance));
        if (replaced != null) {
            replaced.instance().retire();
        }
        return instance;
    }

    /**
     * A plugin held for one attempt; {@code onDone} gives it back.
     */
    public record Lease(OriginPlugin plugin, Runnable onDone) {
    }

    private record Origin(Plugin declaration, Instance instance) {
    }

    private final class Instance {

        private final String name;
        private final OriginPlugin plugin;
        // the attempts holding it, or CLOSED: one field, so that no acquire slips in between a check and the close
        private final AtomicInteger users = new AtomicInteger();
        // replaced or removed: closed once no attempt holds it
        private volatile boolean retired;
        // completes once its plugin has closed
        private final Promise<Void> closing = Promise.promise();

        Instance(String name, OriginPlugin plugin) {
            this.name = name;
            this.plugin = plugin;
        }

        boolean acquire() {
            return users.getAndUpdate(count -> count == CLOSED ? CLOSED : count + 1) != CLOSED;
        }

        void release() {
            // a CLOSED count stays: shutdown closed it under a running attempt
            if (users.getAndUpdate(count -> count == CLOSED ? CLOSED : count - 1) == 1 && retired) {
                closeIfIdle();
            }
        }

        void retire() {
            retired = true;
            closeIfIdle();
        }

        private void closeIfIdle() {
            if (users.compareAndSet(0, CLOSED)) {
                close();
            }
        }

        // closes it whatever attempts hold it, unless it is closing already
        Future<Void> closeNow() {
            return users.getAndSet(CLOSED) == CLOSED ? closing.future() : close();
        }

        private Future<Void> close() {
            // it stays in open until its plugin has closed, so that shutdown waits for a close already running
            closePlugin(name, plugin).onComplete(ignored -> {
                open.remove(this);
                closing.complete();
            });
            return closing.future();
        }
    }

    private Future<Void> closePlugin(String name, com.epam.aidial.core.plugin.Plugin plugin) {
        return taskExecutor.submit(() -> {
            plugin.close();
            return null;
        }).otherwise(error -> {
            log.warn("Plugin '{}' failed to close:", name, error);
            return null;
        }).mapEmpty();
    }
}
