package com.epam.aidial.core.server.upstream;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;

/**
 * A {@link ProxyResponse} received over HTTP.
 */
public record HttpProxyResponse(HttpClientResponse response) implements ProxyResponse {

    @Override
    public int statusCode() {
        return response.statusCode();
    }

    @Override
    public MultiMap headers() {
        return response.headers();
    }

    @Override
    public String getHeader(CharSequence name) {
        return response.getHeader(name);
    }

    @Override
    public Future<Buffer> body() {
        return response.body();
    }

    @Override
    public void discard() {
        // an HTTP answer Core does not read is left to the connection, as it always was
    }

    @Override
    public HttpProxyResponse exceptionHandler(Handler<Throwable> handler) {
        response.exceptionHandler(handler);
        return this;
    }

    @Override
    public HttpProxyResponse handler(Handler<Buffer> handler) {
        response.handler(handler);
        return this;
    }

    @Override
    public HttpProxyResponse pause() {
        response.pause();
        return this;
    }

    @Override
    public HttpProxyResponse resume() {
        response.resume();
        return this;
    }

    @Override
    public HttpProxyResponse fetch(long amount) {
        response.fetch(amount);
        return this;
    }

    @Override
    public HttpProxyResponse endHandler(Handler<Void> endHandler) {
        response.endHandler(endHandler);
        return this;
    }
}
