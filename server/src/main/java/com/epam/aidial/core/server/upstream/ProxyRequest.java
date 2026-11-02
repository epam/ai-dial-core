package com.epam.aidial.core.server.upstream;

import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;

/**
 * The request Core sends to the origin serving a deployment: an HTTP request, or a call to a plugin running in
 * Core. Holds the members of Vert.x {@code HttpClientRequest} that Core uses once a request exists, so the code
 * after the send serves both kinds of origin alike.
 */
public interface ProxyRequest {

    MultiMap headers();

    ProxyRequest putHeader(CharSequence name, CharSequence value);

    Future<ProxyResponse> send();

    Future<ProxyResponse> send(Buffer body);

    /**
     * Stops the request, and the answer if one has arrived.
     */
    boolean reset();

    /**
     * Where the request goes, for logs.
     */
    String remoteAddress();

    String absoluteUri();
}
