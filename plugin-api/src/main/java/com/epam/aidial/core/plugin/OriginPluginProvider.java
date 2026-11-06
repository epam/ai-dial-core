package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;

/**
 * A provider whose {@code plugins} entries serve requests. A deployment interface in {@code embedded} mode names
 * such an entry, and Core calls its {@link OriginPlugin} in place of the HTTP request it would send to the
 * deployment's endpoint.
 */
public interface OriginPluginProvider extends PluginProvider {

    /**
     * Creates the plugin serving one {@code plugins} entry from its settings, which {@link #validate} has accepted.
     * Core calls it on the first request the entry serves and again on the first request after the entry changes,
     * possibly on a Vert.x event loop, so it must be quick and must not block. {@code settings} is read-only.
     *
     * @param vertx Core's Vert.x instance, for the clients and timers the plugin needs: create them here, once,
     *              rather than on the first request
     */
    OriginPlugin create(ObjectNode settings, Vertx vertx);
}
