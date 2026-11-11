package com.epam.aidial.core.plugin;

/**
 * An instance a provider creates from the settings of one {@code plugins} entry. Every kind of plugin extends it, so
 * Core closes every instance it creates, whatever its kind.
 */
public interface Plugin {

    /**
     * Releases what this instance holds: its client pools, caches and timers. Core calls it once per instance: once it
     * no longer uses an instance it replaced or whose entry was removed, and at shutdown, even under work still in
     * flight. It runs on a thread that may block, never on an event loop. An exception thrown here is logged and
     * dropped.
     */
    default void close() {
    }
}
