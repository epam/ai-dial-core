package com.epam.aidial.core.plugin;

import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * A plugin's answer. Core relays it as it relays an HTTP origin's answer: the status, the headers and the body.
 */
public interface OriginResponse {

    int statusCode();

    MultiMap headers();

    /**
     * The body. It emits on the context {@link OriginPlugin#handle} was called on, and only from a task of its own: never
     * from inside a call Core makes to it ({@code handler}, {@code endHandler}, {@code fetch}, {@code resume}), as Core
     * sets its handlers one after another. It flows once Core has set a handler, unless Core pauses it.
     */
    ReadStream<Buffer> body();

    /**
     * Called when Core stops reading the answer early: the client went away, or Core retries past this answer. The
     * plugin stops producing the body and releases what it holds.
     */
    void cancel();
}
