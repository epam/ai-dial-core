package com.epam.aidial.core.server.vertx.stream;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Passes bytes from a writing thread to a reading thread through a queue of a fixed capacity, so that a slow reader
 * blocks the writer instead of growing memory. Every write is queued as one chunk, so write large chunks,
 * for example through a {@link java.io.BufferedOutputStream}. Not related to {@link io.vertx.core.streams.Pipe}.
 *
 * <p>The reader sees the end of the stream only after the writer closes the output. Closing the input
 * fails the pending and all later writes.
 */
public class BoundedPipe {

    private static final byte[] END_PILL = new byte[0];

    private final BlockingQueue<byte[]> queue;
    private final Duration stallTimeout;

    @Getter
    private final OutputStream output = new Output();
    @Getter
    private final InputStream input = new Input();

    private volatile boolean writerClosed;
    private volatile boolean readerClosed;

    /**
     * @param capacity     max number of queued chunks, at least two so that closing the input can wake both sides
     * @param stallTimeout how long a write waits for the reader to free a slot before it fails
     */
    public BoundedPipe(int capacity, Duration stallTimeout) {
        if (capacity < 2) {
            throw new IllegalArgumentException("Capacity must be at least 2: " + capacity);
        }
        if (stallTimeout.isZero() || stallTimeout.isNegative()) {
            throw new IllegalArgumentException("Stall timeout must be positive: " + stallTimeout);
        }

        this.queue = new ArrayBlockingQueue<>(capacity);
        this.stallTimeout = stallTimeout;
    }

    private void put(byte[] chunk) throws IOException {
        checkWriterOpen();
        checkReaderOpen();

        try {
            if (!queue.offer(chunk, stallTimeout.toNanos(), TimeUnit.NANOSECONDS)) {
                throw new IOException("Pipe is stalled, nothing was read for " + stallTimeout);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("Interrupted while waiting for the pipe to be read");
        }

        checkReaderOpen();
    }

    private void checkWriterOpen() throws IOException {
        if (writerClosed) {
            throw new IOException("Output is closed");
        }
    }

    private void checkReaderOpen() throws IOException {
        if (readerClosed) {
            throw new IOException("Input is closed");
        }
    }

    private final class Output extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);

            if (length > 0) {
                put(Arrays.copyOfRange(bytes, offset, offset + length));
            }
        }

        @Override
        public void close() throws IOException {
            if (!writerClosed) {
                put(END_PILL);
                writerClosed = true;
            }
        }
    }

    private final class Input extends InputStream {

        // reads run on different virtual threads one after another, the lock publishes the state between them
        private final ReentrantLock lock = new ReentrantLock();
        private byte[] current;
        private int position;
        private boolean ended;

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);

            if (length == 0) {
                return 0;
            }

            lock.lock();
            try {
                checkReaderOpen();

                if (ended) {
                    return -1;
                }

                if (current == null) {
                    byte[] chunk = take();
                    checkReaderOpen();

                    if (chunk == END_PILL) {
                        ended = true;
                        return -1;
                    }

                    current = chunk;
                    position = 0;
                }

                int size = Math.min(length, current.length - position);
                System.arraycopy(current, position, bytes, offset, size);
                position += size;

                if (position == current.length) {
                    current = null;
                }

                return size;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            readerClosed = true;
            queue.clear();
            // wakes a reader that waits for data, a waiting writer is woken by clearing
            queue.offer(END_PILL);
        }

        private byte[] take() throws IOException {
            try {
                return queue.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for the pipe to be written");
            }
        }
    }
}
