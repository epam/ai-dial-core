package com.epam.aidial.core.plugin;

import io.vertx.core.Future;

/**
 * Answers the requests of the deployment interfaces that name its {@code plugins} entry, in place of an HTTP origin.
 *
 * <p>Core calls {@link #handle} on the Vert.x event loop of the incoming request. A plugin must not block that loop,
 * and must complete the returned future and emit the body of its answer on that same context: the code that relays
 * the answer to the client is not thread-safe.
 *
 * <p>Core closes it as every {@link Plugin}: after the last request of an instance it replaced or whose entry was
 * removed, and at shutdown. After {@link #close}, Core calls the instance only to read or cancel an answer it holds
 * already: one given after {@link OriginHost#cancelled()} completed, or one in flight at shutdown.
 */
public interface OriginPlugin extends Plugin {

    /**
     * Answers one request. A failed future, or an exception thrown here, counts as an origin that failed to send its
     * response headers.
     *
     * @param host calls other deployments for this request, see {@link OriginHost#call}
     */
    Future<OriginResponse> handle(OriginRequest request, OriginHost host);
}
