package com.epam.aidial.core.util;

import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Slf4j
@Getter
public class BufferingReadStream implements ReadStream<Buffer> {

    private final ReadStream<Buffer> stream;
    private final Buffer content;

    private Handler<Buffer> chunkHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;
    private final Function<Buffer, Future<Void>> interceptor;
    private final List<Future<Void>> interceptorCallbacks;

    private Throwable error;
    private boolean ended;
    private boolean reset;

    public BufferingReadStream(ReadStream<Buffer> stream) {
        this(stream, 512, null);
    }

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize) {
        this(stream, initialSize, null);
    }

    public BufferingReadStream(ReadStream<Buffer> stream, int initialSize, Function<Buffer, Future<Void>> interceptor) {
        this.stream = stream;
        this.content = Buffer.buffer(initialSize);
        this.interceptor = interceptor;
        this.interceptorCallbacks = interceptor == null ? null : new ArrayList<>();

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

    private synchronized void handleChunk(Buffer chunk) {
        content.appendBuffer(chunk);
        if (interceptor != null) {
            Future<Void> future = interceptor.apply(chunk).andThen(ignore -> notifyOnChunk(chunk));
            interceptorCallbacks.add(future);
        } else {
            notifyOnChunk(chunk);
        }
    }

    private synchronized void handleEnd(Void ignored) {
        ended = true;
        if (interceptorCallbacks != null) {
            Future.all(interceptorCallbacks).onComplete(ignoredRes -> notifyOnEnd(ignored));
        } else {
            notifyOnEnd(ignored);
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
