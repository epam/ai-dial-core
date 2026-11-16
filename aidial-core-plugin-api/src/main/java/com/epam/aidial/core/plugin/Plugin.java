package com.epam.aidial.core.plugin;

import io.vertx.core.Future;

/**
 * An instance a provider creates from the settings of one {@code plugins} entry. It answers the requests of the
 * deployment interfaces that name the entry, in place of an HTTP origin.
 *
 * <p>Core calls {@link #handle} on the Vert.x event loop of the incoming request. A plugin must not block that loop,
 * and must complete the returned future and emit the body of its answer on that same context: the code that relays
 * the answer to the client is not thread-safe.
 *
 * <p>The requests in flight on an instance Core closes keep using it: Core still reads or cancels their answers, and
 * one that took the instance just before the close may still call {@link #handle}.
 */
public interface Plugin {

    /**
     * Answers one request. A failed future, or an exception thrown here, counts as an origin that failed to send its
     * response headers.
     *
     * @param host calls other deployments for this request, see {@link HostDelegate#call}
     */
    Future<Response> handle(Request request, HostDelegate host);

    /**
     * Releases what this instance holds: its client pools, caches and timers. Core calls it once per instance: when it
     * creates the instance's replacement after the entry changed, at the next request naming the entry after it was
     * removed, and at shutdown. Core does not wait for the requests in flight on the instance: one that wants them to
     * end first waits for them here. It runs on a thread that may block, never on an event loop, so it may wait for an
     * asynchronous release (a Vert.x client's {@code close()} future, for example), but it need not: a plugin that
     * releases its resources asynchronously may start that and return. An exception thrown here is logged and dropped.
     */
    default void close() {
    }
}
