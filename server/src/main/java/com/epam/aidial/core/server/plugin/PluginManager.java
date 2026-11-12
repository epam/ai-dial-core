package com.epam.aidial.core.server.plugin;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.PluginProvider;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The origin plugin instances serving the {@code plugins} entries, one per entry. An instance is created from an
 * entry's settings on the first request it serves and again on the first request after the entry changes. The
 * instance it replaces, or the instance of an entry a request names after it was removed, is closed at once, under the
 * requests still in flight on it; every instance is closed by {@link #close}.
 */
@Slf4j
@RequiredArgsConstructor
public class PluginManager {

    private final PluginRegistry pluginRegistry;
    private final Vertx vertx;
    private final AsyncTaskExecutor taskExecutor;
    private final Map<String, Origin> origins = new ConcurrentHashMap<>();
    private volatile boolean closed;

    /**
     * The plugin serving the entry {@code name} of {@code config}. The plugin of a new or changed entry is created under
     * the map's lock for {@code name}, so requests racing on it share one instance.
     *
     * @throws IllegalStateException when no entry has that name, its provider is not loaded, creating the plugin fails
     *                               or makes one that serves no requests, or Core is shutting down
     */
    public OriginPlugin resolve(Config config, String name) {
        if (closed) {
            throw new IllegalStateException("Core is shutting down");
        }
        Plugin declaration = config.getPlugins().get(name);
        if (declaration == null) {
            Origin removed = origins.remove(name);
            if (removed != null) {
                closePlugin(name, removed.plugin());
            }
            throw new IllegalStateException("No plugins entry is named '" + name + "'");
        }
        Origin cached = origins.get(name);
        if (cached != null && cached.declaration() == declaration) {
            return cached.plugin();
        }
        return origins.compute(name, (key, current) -> {
            if (current != null && current.declaration().equals(declaration)) {
                // a rebuild made a new object for the same declaration: keep it, so the next lookup is an identity check
                return new Origin(declaration, current.plugin());
            }
            // a failed create throws out of compute and leaves the current instance in place
            OriginPlugin plugin = create(name, declaration);
            if (current != null) {
                closePlugin(name, current.plugin());
            }
            return new Origin(declaration, plugin);
        }).plugin();
    }

    /**
     * Closes every plugin, those in use included, and fails every later {@link #resolve}. The future completes once
     * they have closed.
     *
     * <p>ponytail: a plugin that a request creates while this runs stays open; Vert.x, which Core closes next, closes
     * its clients and timers. Track the creates in flight if a plugin ever holds more than that.
     */
    public Future<Void> close() {
        closed = true;
        List<Future<Void>> closes = new ArrayList<>();
        for (String name : origins.keySet()) {
            Origin origin = origins.remove(name);
            if (origin != null) {
                closes.add(closePlugin(name, origin.plugin()));
            }
        }
        return Future.join(closes).mapEmpty();
    }

    private record Origin(Plugin declaration, OriginPlugin plugin) {
    }

    private OriginPlugin create(String name, Plugin declaration) {
        PluginProvider provider = pluginRegistry.get(declaration.getType());
        if (provider == null) {
            throw new IllegalStateException("No plugin provider of type '" + declaration.getType() + "' is loaded");
        }
        com.epam.aidial.core.plugin.Plugin created;
        try {
            created = createOutsideRequest(provider, declaration.getSettings());
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
        return plugin;
    }

    /**
     * Gives the provider its own copy of the settings, so a change it makes cannot turn the entry into a new declaration
     * at every rebuild. Runs it outside the duplicated context of the request that got here first: a timer the plugin
     * sets up there would keep that request's context, and the request it holds, for the plugin's life.
     */
    private com.epam.aidial.core.plugin.Plugin createOutsideRequest(PluginProvider provider, ObjectNode settings) {
        ObjectNode copy = settings == null ? null : settings.deepCopy();
        ContextInternal current = ContextInternal.current();
        if (current == null) {
            return provider.create(copy, vertx);
        }
        ContextInternal base = current.unwrap();
        ContextInternal previous = base.beginDispatch();
        try {
            return provider.create(copy, vertx);
        } finally {
            base.endDispatch(previous);
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
