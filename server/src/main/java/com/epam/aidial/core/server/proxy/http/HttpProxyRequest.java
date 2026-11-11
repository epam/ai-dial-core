package com.epam.aidial.core.server.proxy.http;

import com.epam.aidial.core.server.proxy.ProxyRequest;
import com.epam.aidial.core.server.proxy.ProxyResponse;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;

/**
 * A {@link ProxyRequest} sent over HTTP.
 */
public record HttpProxyRequest(HttpClientRequest request) implements ProxyRequest {

    @Override
    public MultiMap headers() {
        return request.headers();
    }

    @Override
    public ProxyRequest putHeader(CharSequence name, CharSequence value) {
        request.putHeader(name, value);
        return this;
    }

    @Override
    public Future<ProxyResponse> send() {
        return request.send().map(HttpProxyResponse::new);
    }

    @Override
    public Future<ProxyResponse> send(Buffer body) {
        return request.send(body).map(HttpProxyResponse::new);
    }

    @Override
    public boolean reset() {
        return request.reset();
    }

    @Override
    public String remoteAddress() {
        return String.valueOf(request.connection().remoteAddress());
    }

    @Override
    public String absoluteUri() {
        return request.absoluteURI();
    }
}
