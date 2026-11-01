package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.server.function.EncryptedContentWrapFn;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.sse.SseEventListener;
import com.epam.aidial.core.server.sse.SseParser;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.translator.ResponsesToMessagesStream;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.List;

/**
 * An upstream Responses event stream read as the Anthropic Messages event stream it translates to. Each event
 * is stamped by {@link EncryptedContentWrapFn} first, exactly as the Responses pass stamps it, then translated.
 * Flow control passes straight through to the upstream, so a client that stops reading stops the upstream too. An
 * upstream failure ends the translated stream with an {@code error} event, as the translator service ends it, so
 * this stream itself never fails.
 */
@Slf4j
class ResponsesToMessagesReadStream implements ReadStream<Buffer>, SseEventListener {

    private final ReadStream<Buffer> upstream;
    private final ResponsesToMessagesStream translator;
    private final EncryptedContentWrapFn wrapFn;
    private final SseParser parser;

    private Handler<Buffer> handler;
    private Handler<Void> endHandler;
    private boolean ended;

    ResponsesToMessagesReadStream(ReadStream<Buffer> upstream, ResponsesToMessagesStream translator, EncryptedContentWrapFn wrapFn) {
        this.upstream = upstream;
        this.translator = translator;
        this.wrapFn = wrapFn;
        this.parser = new SseParser(512, this);
        upstream.handler(parser::parse);
        // each at most once, and not after the other: an upstream may still report its end after a failure
        upstream.endHandler(ignored -> {
            if (ended) {
                return;
            }
            parser.finish();
            parser.close();
            emit(translator.finish());
            end();
        });
        upstream.exceptionHandler(error -> {
            if (ended) {
                return;
            }
            log.warn("The upstream Responses stream failed: {}", error.getMessage());
            parser.close();
            emit(translator.emitError("api_error", "The upstream connection failed before the response completed"));
            end();
        });
    }

    private void end() {
        ended = true;
        if (endHandler != null) {
            endHandler.handle(null);
        }
    }

    @Override
    public void onEvent(SseEvent event) {
        String data = event.getData();
        if (data == null || data.isBlank()) {
            return;
        }
        JsonNode tree;
        try {
            tree = ProxyUtil.MAPPER.readTree(data);
        } catch (IOException e) {
            // dropped, as the Responses pass drops it: ending the stream here would also lose the usage the
            // terminal event still brings
            log.warn("Skipping a malformed Responses event from upstream: {}", e.getMessage());
            return;
        }
        Future<JsonNode> wrapped = wrapFn.apply(tree);
        emit(translator.handle(wrapped.succeeded() ? wrapped.result() : tree));
    }

    @Override
    public void onComment(String comment) {
        // keep-alive comments describe the upstream connection, not the translated stream
    }

    @Override
    public void onComplete() {
        // the end is handled where the upstream ends, after the parser has flushed its last event
    }

    private void emit(List<String> frames) {
        if (handler == null) {
            return;
        }
        for (String frame : frames) {
            handler.handle(Buffer.buffer(frame));
        }
    }

    @Override
    public ReadStream<Buffer> exceptionHandler(Handler<Throwable> exceptionHandler) {
        // never called: an upstream failure becomes an error event and an end
        return this;
    }

    @Override
    public ReadStream<Buffer> handler(Handler<Buffer> handler) {
        this.handler = handler;
        return this;
    }

    @Override
    public ReadStream<Buffer> pause() {
        upstream.pause();
        return this;
    }

    @Override
    public ReadStream<Buffer> resume() {
        upstream.resume();
        return this;
    }

    @Override
    public ReadStream<Buffer> fetch(long amount) {
        upstream.fetch(amount);
        return this;
    }

    @Override
    public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
        return this;
    }
}
