package com.epam.aidial.core.plugin;

/**
 * An instance a provider creates from the settings of one {@code plugins} entry. Every kind of plugin extends it, so
 * Core closes every instance it creates, whatever its kind.
 */
public interface Plugin {

    /**
     * Releases what this instance holds: its client pools, caches and timers. Core calls it once per instance: when it
     * creates the instance's replacement after the entry changed, at the next request naming the entry after it was
     * removed, and at shutdown. Core does not wait for the requests in flight on the instance: one that wants them to
     * end first waits for them here. It runs on a thread that may block, never on an event loop, so it may wait for an asynchronous release
     * (a Vert.x client's {@code close()} future, for example), but it need not: a plugin that releases its resources
     * asynchronously may start that and return. An exception thrown here is logged and dropped.
     */
    default void close() {
    }
}
