package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.plugin.OriginResponse;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

/**
 * A plugin's answer as a {@link ProxyResponse}: the stream methods go to the plugin's body stream.
 */
final class EmbeddedProxyResponse implements ProxyResponse {

    private final OriginResponse answer;
    private final ReadStream<Buffer> body;
    private boolean cancelled;

    EmbeddedProxyResponse(OriginResponse answer) {
        this.answer = answer;
        this.body = answer.body();
    }

    @Override
    public int statusCode() {
        return answer.statusCode();
    }

    @Override
    public MultiMap headers() {
        return answer.headers();
    }

    @Override
    public String getHeader(CharSequence name) {
        return answer.headers().get(name);
    }

    @Override
    public Future<Buffer> body() {
        Promise<Buffer> promise = Promise.promise();
        Buffer content = Buffer.buffer();
        body.exceptionHandler(promise::tryFail);
        body.endHandler(ignored -> promise.tryComplete(content));
        body.handler(content::appendBuffer);
        body.resume();
        return promise.future();
    }

    @Override
    public void discard() {
        if (!cancelled) {
            cancelled = true;
            answer.cancel();
        }
    }

    @Override
    public EmbeddedProxyResponse exceptionHandler(Handler<Throwable> handler) {
        body.exceptionHandler(handler);
        return this;
    }

    @Override
    public EmbeddedProxyResponse handler(Handler<Buffer> handler) {
        body.handler(handler);
        return this;
    }

    @Override
    public EmbeddedProxyResponse pause() {
        body.pause();
        return this;
    }

    @Override
    public EmbeddedProxyResponse resume() {
        body.resume();
        return this;
    }

    @Override
    public EmbeddedProxyResponse fetch(long amount) {
        body.fetch(amount);
        return this;
    }

    @Override
    public EmbeddedProxyResponse endHandler(Handler<Void> endHandler) {
        body.endHandler(endHandler);
        return this;
    }
}
