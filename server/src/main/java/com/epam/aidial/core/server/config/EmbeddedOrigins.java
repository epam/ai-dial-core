package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Plugin;
import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.OriginPluginProvider;
import com.epam.aidial.core.plugin.PluginProvider;
import io.vertx.core.Vertx;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The origin plugins serving the {@code plugins} entries, created from an entry's settings on the first request it
 * serves and again on the first request after the entry changes.
 */
@RequiredArgsConstructor
public class EmbeddedOrigins {

    private final PluginRegistry pluginRegistry;
    private final Vertx vertx;
    // ponytail: one plugin per name ever declared, never evicted or closed. Add eviction and a close hook once a
    // plugin holds threads or connections.
    private final Map<String, Origin> origins = new ConcurrentHashMap<>();

    /**
     * The plugin serving the entry {@code name} of {@code config}. Takes no lock, as it runs on the event loop: two
     * requests racing on a new declaration may each create a plugin, and the last one is kept.
     *
     * @throws IllegalStateException when no entry has that name, its provider is not loaded or serves no requests,
     *                               or creating the plugin fails
     */
    public OriginPlugin resolve(Config config, String name) {
        Plugin declaration = config.getPlugins().get(name);
        if (declaration == null) {
            throw new IllegalStateException("No plugins entry is named '" + name + "'");
        }
        Origin cached = origins.get(name);
        if (cached != null && cached.declaration().equals(declaration)) {
            if (cached.declaration() != declaration) {
                // a rebuild made a new object for the same declaration: keep it, so the next equals is an identity check
                origins.put(name, new Origin(declaration, cached.plugin()));
            }
            return cached.plugin();
        }
        PluginProvider provider = pluginRegistry.get(declaration.getType());
        if (provider == null) {
            throw new IllegalStateException("No plugin provider of type '" + declaration.getType() + "' is loaded");
        }
        if (!(provider instanceof OriginPluginProvider originProvider)) {
            throw new IllegalStateException("The plugin provider of type '" + declaration.getType() + "' serves no requests");
        }
        OriginPlugin plugin;
        try {
            plugin = originProvider.create(declaration.getSettings(), vertx);
        } catch (RuntimeException | LinkageError e) {
            // plugin code: a jar missing a class throws NoClassDefFoundError on first use
            throw new IllegalStateException("Plugin '" + name + "' cannot be created: " + e.getMessage(), e);
        }
        origins.put(name, new Origin(declaration, plugin));
        return plugin;
    }

    private record Origin(Plugin declaration, OriginPlugin plugin) {
    }
}
