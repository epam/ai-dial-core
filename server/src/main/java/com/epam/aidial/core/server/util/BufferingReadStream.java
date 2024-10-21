package com.epam.aidial.core.server.util;

import com.epam.aidial.core.server.function.BaseResponseFunction;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

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
    // set the position to unset by default
    private int lastChunkPos = -1;
    private final EventStreamParser eventStreamParser;
    private Future<Boolean> streamHandlerFuture;

    public BufferingReadStream(ReadStream<Buffer> stream) {
        this(stream, 512, null);
    }

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize) {
        this(stream, initialSize, null);
    }

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize, BaseResponseFunction streamHandler) {
        this.stream = stream;
        this.content = Buffer.buffer(initialSize);
        if (streamHandler == null) {
            this.eventStreamParser = null;
        } else {
            this.eventStreamParser = new EventStreamParser(512, streamHandler);
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

    public synchronized void end(HttpServerResponse response) {
        if (lastChunkPos != -1) {
            Buffer lastChunk = content.slice(lastChunkPos, content.length());
            response.end(lastChunk);
        } else {
            response.end();
        }
    }

    private synchronized void handleChunk(Buffer chunk) {
        int pos = content.length();
        content.appendBuffer(chunk);
        if (lastChunkPos != -1) {
            // stop streaming
            return;
        }
        if (eventStreamParser != null) {
            // build chain of chunk futures: the chunks should be sent in the same order as they arrive
            if (streamHandlerFuture == null) {
                streamHandlerFuture = parseChunk(chunk, pos);
            } else {
                streamHandlerFuture = streamHandlerFuture.transform(ignore -> parseChunk(chunk, pos));
            }
        } else {
            notifyOnChunk(chunk);
        }
    }

    private synchronized Future<Boolean> parseChunk(Buffer chunk, int pos) {
        return eventStreamParser.parse(chunk)
                .andThen(result -> handleStreamEvent(chunk, result.result() == Boolean.TRUE, pos));
    }

    private synchronized void handleStreamEvent(Buffer chunk, boolean isLastChunk, int pos) {
        if (isLastChunk) {
            if (lastChunkPos == -1) {
                lastChunkPos = pos;
            }
            // don't send the last chunk
            return;
        }
        notifyOnChunk(chunk);
    }

    private synchronized void handleEnd(Void ignored) {
        ended = true;
        if (streamHandlerFuture == null) {
            notifyOnEnd(ignored);
        } else {
            streamHandlerFuture.onComplete(ignore -> notifyOnEnd(ignored));
        }
    }

    private synchronized void handleException(Throwable exception) {
        error = exception;
        ended = true;
        notifyOnException(exception);
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
}
