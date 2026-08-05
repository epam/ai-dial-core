package com.epam.aidial.core.server.vertx.stream;

import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.sse.SseEvent;
import com.epam.aidial.core.server.sse.SseEventListener;
import com.epam.aidial.core.server.sse.SseParser;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.Getter;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;
import javax.annotation.Nullable;

@Slf4j
@Getter
public class BufferingReadStream implements ReadStream<Buffer> {

    private final ReadStream<Buffer> stream;
    private final Buffer content;

    private Handler<Buffer> chunkHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;

    private Throwable error;
    private boolean ended;
    private boolean reset;
    private boolean eventStreamParserClosed;
    private final SseParser eventStreamParser;
    private final BaseEventListener eventListener;
    // promise on input stream is completed
    private final Promise<Void> endStream;

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize) {
        this(stream, initialSize, null);
    }

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize, @Nullable BaseEventListener listener) {
        this.stream = stream;
        this.content = Buffer.buffer(initialSize);
        this.endStream = Promise.promise();
        this.eventListener = listener;
        if (listener == null) {
            this.eventStreamParser = null;
        } else {
            listener.chunkHandler(this::notifyOnChunk);
            listener.chunkEndHandler(this::handleEndInternal);
            this.eventStreamParser = new SseParser(512, listener);
        }

        stream.handler(this::handleChunk);
        stream.endHandler(this::handleEnd);
        stream.exceptionHandler(this::handleException);
    }

    @Override
    public synchronized Pipe<Buffer> pipe() {
        pause();
        reset = true;
        return new PipeImpl<>(this);
    }

    @Override
    public synchronized ReadStream<Buffer> pause() {
        if (!ended) {
            try {
                stream.pause();
            } catch (Throwable e) {
                log.warn("Stream.pause() threw exception: {}", e.getMessage());
            }
        }

        return this;
    }

    @Override
    public synchronized ReadStream<Buffer> resume() {
        fetch(Long.MAX_VALUE);
        return this;
    }

    @Override
    public synchronized ReadStream<Buffer> fetch(long amount) {
        if (reset) {
            reset = false;

            if (error == null) {
                if (content.length() > 0) {
                    notifyOnChunk(content.slice());
                }

                if (ended) {
                    notifyOnEnd(null);
                }
            } else {
                notifyOnException(error);
            }
        }

        if (!ended) {
            try {
                stream.fetch(amount);
            } catch (Throwable e) {
                log.warn("Stream.fetch() threw exception: {}", e.getMessage());
            }
        }

        return this;
    }

    @Override
    public synchronized ReadStream<Buffer> handler(Handler<Buffer> handler) {
        chunkHandler = handler;
        return this;
    }

    @Override
    public synchronized ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
        exceptionHandler = handler;
        return this;
    }

    @Override
    public synchronized ReadStream<Buffer> endHandler(Handler<Void> handler) {
        endHandler = handler;
        return this;
    }

    public void end(HttpServerResponse response) {
        Buffer lastChunk = null;
        if (eventListener != null) {
            lastChunk = eventListener.lastChunk;
        }
        if (lastChunk != null) {
            response.end(lastChunk);
        } else {
            response.end();
        }
    }

    public Future<Void> endStreamFuture() {
        return endStream.future();
    }

    private synchronized void handleChunk(Buffer chunk) {
        content.appendBuffer(chunk);
        if (eventStreamParser != null) {
            eventStreamParser.parse(chunk);
        } else {
            notifyOnChunk(chunk);
        }
    }

    private synchronized void handleEnd(Void ignored) {
        ended = true;
        if (eventListener == null) {
            handleEndInternal(ignored);
        } else {
            eventStreamParser.finish();
            closeEventStreamParser();
        }
    }

    private void handleEndInternal(Void ignored) {
        endStream.tryComplete();
        notifyOnEnd(ignored);
    }

    private synchronized void handleException(Throwable exception) {
        error = exception;
        ended = true;
        closeEventStreamParser();
        endStream.tryFail(exception);
        notifyOnException(exception);
    }

    private void closeEventStreamParser() {
        if (eventStreamParser != null && !eventStreamParserClosed) {
            eventStreamParserClosed = true;
            eventStreamParser.close();
        }
    }

    private synchronized void notifyOnChunk(Buffer chunk) {
        if (chunkHandler != null) {
            try {
                chunkHandler.handle(chunk);
            } catch (Throwable e) {
                log.warn("Chunk handler threw exception buffering read stream: {}", e.getMessage());
            }
        }
    }

    private synchronized void notifyOnEnd(Void ignored) {
        if (endHandler != null) {
            try {
                endHandler.handle(ignored);
            } catch (Throwable e) {
                log.warn("End handler threw exception buffering read stream: {}", e.getMessage());
            }
        }
    }

    private void notifyOnException(Throwable throwable) {
        if (exceptionHandler != null) {
            try {
                exceptionHandler.handle(throwable);
            } catch (Throwable e) {
                log.warn("Exception handler threw exception in buffering read stream: {}", throwable.getMessage());
            }
        }
    }

    @Slf4j
    public static class BaseEventListener implements SseEventListener {
        private final List<BaseResponseFunction> functions;
        // a chain of futures supplied by SSE parser
        private Future<Void> streamHandlerChain = Future.succeededFuture();
        private Handler<Buffer> chunkHandler;
        private Handler<Void> chunkEndHandler;
        private volatile Buffer lastChunk;
        // withheld terminal event/tree, kept alongside lastChunk so rewriteLastChunk can re-render it
        private volatile SseEvent lastEvent;
        private volatile JsonNode lastTree;

        public BaseEventListener(List<BaseResponseFunction> functions) {
            this.functions = functions;
        }

        /**
         * Rewrites the withheld terminal event before it's flushed by {@link BufferingReadStream#end}.
         * A no-op if the terminal event isn't real JSON (chat completions withholds the literal
         * {@code [DONE]}, see {@link #skipEvent}) - that flavor has nothing here to rewrite and
         * injects an extra chunk instead.
         */
        public void rewriteLastChunk(UnaryOperator<JsonNode> fn) {
            if (lastTree != null) {
                lastChunk = to(lastEvent, fn.apply(lastTree));
            }
        }

        @Override
        public void onEvent(SseEvent event) {
            try {
                streamHandlerChain = streamHandlerChain.transform(ignore -> handle(event));
            } catch (Throwable e) {
                log.error("Error occurred at handling SSE event", e);
            }
        }

        @Override
        public void onComment(String comment) {
            Buffer chunk = Buffer.buffer(":"  + comment + "\n");
            // we don't enforce sending order for comments
            chunkHandler.handle(chunk);
        }

        public void chunkHandler(Handler<Buffer> handler) {
            this.chunkHandler = Objects.requireNonNull(handler, "Chunk handler must not be null");
        }

        public void chunkEndHandler(Handler<Void> handler) {
            chunkEndHandler = Objects.requireNonNull(handler, "Chunk end handler must not be null");
        }

        @Override
        public void onComplete() {
            streamHandlerChain.onComplete(ignored -> chunkEndHandler.handle(null));
        }

        @SneakyThrows
        private Future<Void> handle(SseEvent event) {
            Future<JsonNode> result;
            if (functions.isEmpty() || skipEvent(event)) {
                result = Future.succeededFuture();
            } else {
                String data = event.getData();
                try {
                    JsonNode tree = ProxyUtil.MAPPER.readTree(data);
                    result = Future.succeededFuture(tree);
                    for (BaseResponseFunction fn : functions) {
                        result = result.compose(fn);
                    }
                } catch (Throwable error) {
                    log.warn("Error occurred at JSON data parsing of SSE data and function calling", error);
                    result = Future.failedFuture(error);
                }
            }
            return result.recover(error -> {
                log.warn("Function call is failed with error. Try to recover SSE event", error);
                return recover(error, event);
            }).map(json -> send(event, json));
        }

        protected Future<JsonNode> recover(Throwable error, SseEvent event) {
            // default logic for recovering
            return Future.succeededFuture();
        }

        private Void send(SseEvent event, @Nullable JsonNode tree) {
            if (isLastEvent(event, tree)) {
                // we send the last chunk later
                lastEvent = event;
                lastTree = tree;
                lastChunk = to(event, tree);
            } else if (lastChunk == null) {
                Buffer chunk = to(event, tree);
                chunkHandler.handle(chunk);
            }
            return null;
        }

        private static Buffer to(SseEvent originalEvent, @Nullable JsonNode tree) {
            String rawEvent;
            if (tree == null) {
                rawEvent = originalEvent.toString();
            } else {
                String json = tree.toString();
                SseEvent event = originalEvent.copyWith(json);
                rawEvent = event.toString();
            }
            return Buffer.buffer(rawEvent);
        }

        protected boolean isLastEvent(SseEvent event, @Nullable JsonNode tree) {
            return false;
        }

        protected boolean skipEvent(SseEvent event) {
            return false;
        }

    }
}
