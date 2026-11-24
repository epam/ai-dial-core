package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Vertx;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * The code behind one plugin {@link #id()}: every {@code plugins} config entry whose {@code pluginId} names it is
 * served by it.
 *
 * <p>Core finds providers once at start with {@link java.util.ServiceLoader}, in the jars of its plugins directory and
 * on its own classpath. A plugin jar registers its provider in
 * {@code META-INF/services/com.epam.aidial.core.plugin.PluginProvider}, and the class needs a public no-argument
 * constructor. Two providers declaring the same {@link #id()} stop Core from starting.
 */
public interface PluginProvider {

    /**
     * The value of {@code pluginId} in the {@code plugins} entries this provider serves, for example {@code forward}.
     * It names the code; an entry's name, which a base url {@code dial-plugin://<name>} gives, names one configured
     * instance of it. Several entries may share an id, each with its own settings. Core calls only this method; the
     * default returns {@link #type()}, so a provider built before {@code id()} existed loads unchanged. A provider
     * overrides this method, and a null or blank id stops Core from starting.
     */
    default String id() {
        return type();
    }

    /**
     * The id under its former name, read only through the default {@link #id()}.
     *
     * @deprecated override {@link #id()} instead
     */
    @Deprecated(forRemoval = true)
    default String type() {
        return null;
    }

    /**
     * What the plugin does, in a sentence for the operator who picks it in the admin console, or null. Core reads it
     * once, at start, and lists it at {@code GET /v1/admin/plugin-providers}.
     */
    default String description() {
        return null;
    }

    /**
     * Where a plugin url may name the entries of this provider, all of {@link PluginTarget} by default. A hint for the
     * admin console only: Core does not check a plugin url against it. Core reads it once, at start; an empty set, a
     * null set or a null in it stops Core from starting.
     */
    default Set<PluginTarget> targets() {
        return EnumSet.allOf(PluginTarget.class);
    }

    /**
     * The JSON Schema (draft-07) the {@code settings} of an entry must match, or null for none. Core reads it once, at
     * start, and checks the settings against it on every write of an entry and on every config rebuild, before
     * {@link #validate}, which it skips when the schema rejects them. The schema must be self-contained: a
     * {@code $schema} other than draft-07, or a {@code $ref} to anything but a place in the schema itself or the
     * draft-07 meta-schema, stops Core from starting, and so does a schema that is not valid draft-07. Core loads
     * nothing a schema refers to. Core also hands the schema to the admin console through
     * {@code GET /v1/admin/plugin-providers}.
     */
    default ObjectNode settingsSchema() {
        return null;
    }

    /**
     * Checks the {@code settings} of one {@code plugins} entry, after the {@link #settingsSchema()}, if any, accepted
     * them. Core calls it on every write of the entry and on every config rebuild, from several threads at once and on
     * a Vert.x event loop too, so it must be quick and thread-safe and must not block or change {@code settings}. An
     * exception it throws counts as a rejection of the settings. The default accepts everything, for a provider whose
     * schema says all its rules.
     *
     * @return the problems found, empty when the settings are valid
     */
    default List<Problem> validate(ObjectNode settings) {
        return List.of();
    }

    /**
     * Creates the plugin serving one {@code plugins} entry from its settings, which {@link #validate} has accepted.
     * Core calls it on the first request the entry serves and again on the first request after the entry changes,
     * possibly on a Vert.x event loop, so it must be quick and must not block. {@code settings} is read-only.
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
