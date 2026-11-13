package com.epam.aidial.core.server.proxy;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * The origin's answer to a {@link ProxyRequest}: the members of Vert.x {@code HttpClientResponse} that Core reads.
 */
public interface ProxyResponse extends ReadStream<Buffer> {

    int statusCode();

    MultiMap headers();

    String getHeader(CharSequence name);

    /**
     * The whole body. Use either this or the stream, not both.
     */
    Future<Buffer> body();

    /**
     * Called on an answer Core drops unread, such as one it retries past.
     */
    void discard();
}
