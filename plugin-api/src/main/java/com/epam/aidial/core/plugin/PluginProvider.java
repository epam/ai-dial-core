package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;

import java.util.List;

/**
 * The code behind one plugin {@code type}: every {@code plugins} config entry naming that type is served by it.
 *
 * <p>Core finds providers once at start with {@link java.util.ServiceLoader}, in the jars of its plugins directory and
 * on its own classpath. A plugin jar registers its provider in
 * {@code META-INF/services/com.epam.aidial.core.plugin.PluginProvider}, and the class needs a public no-argument
 * constructor. Two providers declaring the same {@link #type()} stop Core from starting.
 */
public interface PluginProvider {

    /**
     * The value of {@code type} in the {@code plugins} entries this provider serves, for example {@code forward}. It
     * names the code; an entry's name, which a deployment interface gives as its {@code plugin}, names one configured
     * instance of it. Several entries may share a type, each with its own settings.
     */
    String type();

    /**
     * Checks the {@code settings} of one {@code plugins} entry. Core calls it on every write of the entry and on every
     * config rebuild, from several threads at once and on a Vert.x event loop too, so it must be quick and thread-safe
     * and must not block or change {@code settings}. An exception it throws counts as a rejection of the settings.
     *
     * @return the problems found, empty when the settings are valid
     */
    List<Problem> validate(ObjectNode settings);

    /**
     * Creates the plugin serving one {@code plugins} entry from its settings, which {@link #validate} has accepted.
     * Core calls it on the first request the entry serves and again on the first request after the entry changes,
     * possibly on a Vert.x event loop, so it must be quick and must not block. {@code settings} is read-only.
     *
     * <p>The plugin's kind decides what the entry can serve: an {@link OriginPlugin} answers the requests of the
     * deployment interfaces that name the entry. Core closes an instance of a kind the entry's use does not take, and
     * fails that use.
     *
     * @param vertx Core's Vert.x instance, for the clients and timers the plugin needs: create them here, once,
     *              rather than on the first request
     */
    Plugin create(ObjectNode settings, Vertx vertx);

    /**
     * One thing wrong with the settings.
     *
     * @param path    where in {@code settings} the problem is, as dot-separated field names, or empty for the whole object
     * @param message what is wrong, for the operator who wrote the entry
     */
    record Problem(String path, String message) {
    }
}
