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
 * Flow control and failures pass straight through to the upstream, so a client that stops reading stops the
 * upstream too, and a reset of the upstream reaches whoever reads this.
 */
@Slf4j
class ResponsesToMessagesReadStream implements ReadStream<Buffer>, SseEventListener {

    private final ReadStream<Buffer> upstream;
    private final ResponsesToMessagesStream translator;
    private final EncryptedContentWrapFn wrapFn;
    private final SseParser parser;

    private Handler<Buffer> handler;
    private Handler<Void> endHandler;

    ResponsesToMessagesReadStream(ReadStream<Buffer> upstream, ResponsesToMessagesStream translator, EncryptedContentWrapFn wrapFn) {
        this.upstream = upstream;
        this.translator = translator;
        this.wrapFn = wrapFn;
        this.parser = new SseParser(512, this);
        upstream.handler(parser::parse);
        upstream.endHandler(ignored -> {
            parser.finish();
            parser.close();
            emit(translator.finish());
            if (endHandler != null) {
                endHandler.handle(null);
            }
        });
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
        upstream.exceptionHandler(error -> {
            parser.close();
            if (exceptionHandler != null) {
                exceptionHandler.handle(error);
            }
        });
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
