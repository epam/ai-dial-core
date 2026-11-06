package com.epam.aidial.core.plugin;

import com.fasterxml.jackson.databind.node.ObjectNode;

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
     * The value of {@code type} in the {@code plugins} entries this provider serves.
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
     * One thing wrong with the settings.
     *
     * @param path    where in {@code settings} the problem is, as dot-separated field names, or empty for the whole object
     * @param message what is wrong, for the operator who wrote the entry
     */
    record Problem(String path, String message) {
    }
}
