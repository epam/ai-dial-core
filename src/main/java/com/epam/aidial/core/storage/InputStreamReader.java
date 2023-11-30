package com.epam.aidial.core.storage;

import io.netty.buffer.Unpooled;
import io.vertx.core.AsyncResult;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.buffer.impl.BufferImpl;
import io.vertx.core.streams.Pipe;
import io.vertx.core.streams.ReadStream;
import io.vertx.core.streams.WriteStream;
import io.vertx.core.streams.impl.InboundBuffer;
import io.vertx.core.streams.impl.PipeImpl;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;

/**
 * Implementation of vertx {@link io.vertx.core.streams.ReadStream} that wraps {@link java.io.InputStream}.
 */
@Slf4j
public class InputStreamReader implements ReadStream<Buffer> {

    private static final int DEFAULT_READ_BUFFER_SIZE = 32768;

    private final Vertx vertx;
    private final InputStream in;
    private final InboundBuffer<Buffer> queue;
    private final int bufferSize;

    private Handler<Buffer> dataHandler;
    private Handler<Void> endHandler;
    private Handler<Throwable> exceptionHandler;

    public InputStreamReader(Vertx vertx, InputStream stream) {
        this(vertx, stream, DEFAULT_READ_BUFFER_SIZE);
    }

    public InputStreamReader(Vertx vertx, InputStream in, int bufferSize) {
        this.vertx = vertx;
        this.in = in;
        this.queue = new InboundBuffer<>(vertx.getOrCreateContext(), 32);
        this.bufferSize = bufferSize;
        queue.handler(buff -> {
            if (buff.length() > 0) {
                handleData(buff);
            } else {
                handleEnd();
            }
        });
        queue.drainHandler(v -> readDataFromStream());
        queue.pause();
        readDataFromStream();
    }

    @Override
    public synchronized InputStreamReader endHandler(Handler<Void> endHandler) {
        this.endHandler = endHandler;
        return this;
    }

    @Override
    public synchronized InputStreamReader exceptionHandler(Handler<Throwable> exceptionHandler) {
        this.exceptionHandler = exceptionHandler;
        return this;
    }

    @Override
    public void pipeTo(WriteStream<Buffer> dst, Handler<AsyncResult<Void>> handler) {
        Pipe<Buffer> pipe = new PipeImpl<>(this).endOnFailure(false);
        pipe.to(dst, handler);
    }

    @Override
    public synchronized InputStreamReader handler(Handler<Buffer> handler) {
        this.dataHandler = handler;
        return this;
    }

    @Override
    public synchronized InputStreamReader pause() {
        queue.pause();
        return this;
    }

    @Override
    public synchronized InputStreamReader resume() {
        queue.resume();
        return this;
    }

    @Override
    public synchronized ReadStream<Buffer> fetch(long amount) {
        queue.fetch(amount);
        return this;
    }

    private void readDataFromStream() {
        Future<Buffer> fetchResult = vertx.executeBlocking(() -> {
            try {
                byte[] data = in.readNBytes(bufferSize);
                return BufferImpl.buffer(Unpooled.wrappedBuffer(data));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        fetchResult.onSuccess(buf -> {
            if (queue.write(buf) && buf.length() > 0) {
                // load more data
                readDataFromStream();
            }
        }).onFailure(error -> {
            log.info("Failed to read data from InputStream", error);
            close();
            synchronized (InputStreamReader.this) {
                if (exceptionHandler != null) {
                    exceptionHandler.handle(error);
                }
            }
        });
    }

    private void handleData(Buffer buff) {
        Handler<Buffer> handler;
        synchronized (this) {
            handler = dataHandler;
        }
        if (handler != null) {
            handler.handle(buff);
        }
    }

    private synchronized void handleEnd() {
        close();
        this.dataHandler.handle(Buffer.buffer());
        dataHandler = null;
        this.endHandler.handle(null);
    }

    public synchronized void close() {
        try {
            in.close();
            queue.clear();
        } catch (Exception ex) {
            // ignore
            log.warn("Failed to close InputStream", ex);
        }
    }
}