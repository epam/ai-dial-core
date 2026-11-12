package com.epam.aidial.core.server.vertx.stream;

import io.vertx.core.buffer.Buffer;
import io.vertx.core.streams.ReadStream;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class InputStreamAdapter extends InputStream {

    private static final Buffer END_PILL = Buffer.buffer();
    private static final int LOW_MEMORY_BYTES = 1024 * 1024;
    private static final int HIGH_MEMORY_BYTES = 4 * 1024 * 1024;

    private final BlockingQueue<Buffer> queue = new LinkedBlockingQueue<>();
    private final AtomicLong queuedMemorySize = new AtomicLong();
    private final ReadStream<Buffer> stream;
    /**
     * How long {@link #read(byte[], int, int)} waits for the next chunk before failing as stalled. Counts only
     * time spent waiting for the client's next bytes: a caller busy elsewhere between calls, or blob storage
     * pausing the stream, is not a stall. {@code null} (the default, used by every caller but a system import)
     * waits forever, as before this field existed.
     */
    private final Duration readTimeout;

    private volatile IOException error;
    private Buffer current;
    private int position;

    public InputStreamAdapter(ReadStream<Buffer> stream) {
        this(stream, null);
    }

    public InputStreamAdapter(ReadStream<Buffer> stream, Duration readTimeout) {
        this.stream = stream;
        this.readTimeout = readTimeout;
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
                    current = take();

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

    /**
     * Waits for the next chunk, failing with an {@link IOException} once {@link #readTimeout} elapses with
     * nothing in the queue. A chunk already queued (including {@link #END_PILL}, added by {@link #onEnd} or
     * {@link #onError}) is returned at once, so the timeout never fires once the client has finished or failed.
     */
    private Buffer take() throws InterruptedException, IOException {
        if (readTimeout == null) {
            return queue.take();
        }

        Buffer buffer = queue.poll(readTimeout.toMillis(), TimeUnit.MILLISECONDS);
        if (buffer == null) {
            StallTimeoutException timeout = new StallTimeoutException("No data received for " + readTimeout.toMillis() + " ms");
            error = timeout;
            throw timeout;
        }
        return buffer;
    }

    /**
     * Thrown by {@link #read(byte[], int, int)} when {@link #readTimeout} elapses with no bytes from the client,
     * distinct from a transport error or a corrupt body so a caller can answer {@code 408} rather than {@code 400}.
     */
    public static final class StallTimeoutException extends IOException {
        public StallTimeoutException(String message) {
            super(message);
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
