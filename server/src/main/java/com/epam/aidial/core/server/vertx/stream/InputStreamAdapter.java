package com.epam.aidial.core.server.vertx.stream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

public class InputStreamAdapter extends InputStream {

    private static final Buffer END_PILL = Buffer.buffer();
    private static final int LOW_MEMORY_BYTES = 1024 * 1024;
    private static final int HIGH_MEMORY_BYTES = 4 * 1024 * 1024;

    private final BlockingQueue<Buffer> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queuedMemorySize = new AtomicLong();
    private final ReadStream<Buffer> stream;

    private volatile IOException error;
    private Buffer current;
    private int position;

    public InputStreamAdapter(ReadStream<Buffer> stream) {
        this.stream = stream;
        stream.handler(this::onData)
                .endHandler(this::onEnd)
                .exceptionHandler(this::onError);
    }

    private void onData(Buffer data) {
        if (data.length() > 0 && error == null) {
            queue.add(data);
            update(data.length());
        }
    }

    private void onEnd(Void data) {
        if (error == null) {
            queue.add(END_PILL);
        }
    }

    private void onError(Throwable exception) {
        if (error == null) {
            error = new IOException(exception);
            queue.add(END_PILL);
        }
    }

    @Override
    public int read() throws IOException {
        // so dumb - not really used
        byte[] array = new byte[1];
        int size = read(array, 0, 1);
        return (size <= 0) ? -1 : (array[0] & 0xFF);
    }

    @Override
    public synchronized int read(byte[] array, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, array.length);

        if (error != null) {
            throw error;
        }

        if (current == END_PILL) {
            return -1;
        }

        if (length == 0) {
            return 0;
        }

        try {
            int size = 0;

            while (size < length) {
                if (current == null) {
                    current = queue.take();

                    if (error != null) {
                        throw error;
                    }

                    if (current == END_PILL) {
                        break;
                    }
                }

                int chunk = Math.min(length - size, current.length() - position);
                current.getBytes(position, position + chunk, array, offset + size);
                position += chunk;
                size += chunk;

                if (position == current.length()) {
                    update(-current.length());
                    current = null;
                    position = 0;
                }
            }

            return size == 0 ? -1 : size;
        } catch (InterruptedException e) {
            error = new IOException(e);
            throw new IOException(error);
        }
    }

    private void update(long delta) {
        long size = queuedMemorySize.addAndGet(delta);
        if (size <= LOW_MEMORY_BYTES) {
            stream.fetch(HIGH_MEMORY_BYTES - size);
        } else if (size >= HIGH_MEMORY_BYTES) {
            stream.pause();
        }
    }

    @Override
    public void close() {
        if (error == null) {
            error = new IOException("closed");
            queue.add(END_PILL);
        }
    }
}
