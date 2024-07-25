package com.epam.aidial.core.util;

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
    private final boolean isStreaming;
    private Buffer lastChunk;

    public BufferingReadStream(ReadStream<Buffer> stream) {
        this(stream, 512, false);
    }

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize) {
        this(stream, initialSize, false);
    }

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize, boolean isStreaming) {
        this.stream = stream;
        this.content = Buffer.buffer(initialSize);
        this.isStreaming = isStreaming;

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
        if (lastChunk != null) {
            response.end(lastChunk);
        } else {
            response.end();
        }
    }

    private synchronized void handleChunk(Buffer chunk) {
        if (lastChunk != null) {
            // stop streaming
            return;
        }
        content.appendBuffer(chunk);
        if (isStreaming && isLastChunk(content)) {
            lastChunk = chunk;
        } else {
            notifyOnChunk(chunk);
        }
    }

    private static boolean isLastChunk(Buffer content) {
        int i = skipWhitespaces(content, content.length() - 1);
        String lastMessage = "data: [DONE]";
        int j = lastMessage.length() - 1;
        for (; i >= 0 && j >= 0; i--, j--) {
            if (content.getByte(i) != lastMessage.charAt(j)) {
                return false;
            }
        }
        return true;
    }

    private static int skipWhitespaces(Buffer content, int i) {
        for (; i >= 0; i--) {
            byte b = content.getByte(i);
            if (!Character.isWhitespace(b)) {
                break;
            }
        }
        return i;
    }

    private synchronized void handleEnd(Void ignored) {
        ended = true;
        notifyOnEnd(ignored);
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
