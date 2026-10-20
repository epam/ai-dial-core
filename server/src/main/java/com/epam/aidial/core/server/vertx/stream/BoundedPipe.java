package com.epam.aidial.core.server.vertx.stream;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Passes bytes from a writing thread to a reading thread through a queue of a fixed capacity, so that a slow reader
 * blocks the writer instead of growing memory. Every write is queued as one chunk, so write large chunks,
 * for example through a {@link java.io.BufferedOutputStream}. Both ends block, so neither may run on an event loop.
 * Not related to {@link io.vertx.core.streams.Pipe}.
 *
 * <p>The reader sees the end of the stream only after the writer closes the output. Close the output only after
 * everything is written, and call {@link #closeExceptionally(Throwable)} on a failure instead, because closing ends
 * the stream cleanly.
 *
 * <p>A write that stalls or is interrupted, and {@link #closeExceptionally(Throwable)}, fail the reader and all later
 * writes. Closing the input fails the pending and all later writes.
 */
public class BoundedPipe {

    private final int capacity;
    private final Duration stallTimeout;

    // guards all the state below, and publishes it between reads that run on different virtual threads
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final ArrayDeque<byte[]> queue;
    private boolean writerClosed;
    private boolean readerClosed;
    private IOException failure;
    private byte[] current;
    private int position;

    @Getter
    private final OutputStream output = new Output();
    @Getter
    private final InputStream input = new Input();

    /**
     * @param capacity     max number of queued chunks
     * @param stallTimeout how long a write waits for the reader to free a slot before it fails
     */
    public BoundedPipe(int capacity, Duration stallTimeout) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        if (stallTimeout.isZero() || stallTimeout.isNegative()) {
            throw new IllegalArgumentException("Stall timeout must be positive: " + stallTimeout);
        }

        this.capacity = capacity;
        this.stallTimeout = stallTimeout;
        this.queue = new ArrayDeque<>(capacity);
    }

    /**
     * Fails the pending and all later reads and writes with the cause. Can be called from any thread.
     */
    public void closeExceptionally(Throwable cause) {
        lock.lock();
        try {
            fail(new IOException("Pipe is aborted: " + cause, cause));
        } finally {
            lock.unlock();
        }
    }

    private void put(byte[] chunk) throws IOException {
        long remainingNanos = stallTimeout.toNanos();

        lock.lock();
        try {
            while (true) {
                checkWritable();

                if (queue.size() < capacity) {
                    break;
                }

                if (remainingNanos <= 0) {
                    throw fail(new IOException("Pipe is stalled, nothing was read for " + stallTimeout));
                }

                try {
                    remainingNanos = notFull.awaitNanos(remainingNanos);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw fail(new InterruptedIOException("Interrupted while waiting for the pipe to be read"));
                }
            }

            queue.add(chunk);
            notEmpty.signal();
        } finally {
            lock.unlock();
        }
    }

    // the lock must be held
    private <E extends IOException> E fail(E error) {
        if (failure == null) {
            failure = error;
        }
        discard();
        return error;
    }

    // the lock must be held
    private void discard() {
        queue.clear();
        current = null;
        notEmpty.signalAll();
        notFull.signalAll();
    }

    // the lock must be held
    private void checkWritable() throws IOException {
        checkReadable();

        if (writerClosed) {
            throw new IOException("Output is closed");
        }
    }

    // the lock must be held
    private void checkReadable() throws IOException {
        if (failure != null) {
            throw new IOException(failure.getMessage(), failure);
        }

        if (readerClosed) {
            throw new IOException("Input is closed");
        }
    }

    private final class Output extends OutputStream {

        @Override
        public void write(int b) throws IOException {
            put(new byte[] {(byte) b});
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);

            if (length > 0) {
                put(Arrays.copyOfRange(bytes, offset, offset + length));
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                writerClosed = true;
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
        }
    }

    private final class Input extends InputStream {

        @Override
        public int read() throws IOException {
            lock.lock();
            try {
                if (!fill()) {
                    return -1;
                }

                int value = current[position] & 0xFF;
                advance(1);
                return value;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);

            if (length == 0) {
                return 0;
            }

            lock.lock();
            try {
                if (!fill()) {
                    return -1;
                }

                int size = Math.min(length, current.length - position);
                System.arraycopy(current, position, bytes, offset, size);
                advance(size);
                return size;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void close() {
            lock.lock();
            try {
                readerClosed = true;
                discard();
            } finally {
                lock.unlock();
            }
        }

        // the lock must be held, returns false at the end of the stream
        private boolean fill() throws IOException {
            while (current == null) {
                checkReadable();

                byte[] chunk = queue.poll();

                if (chunk != null) {
                    current = chunk;
                    position = 0;
                    notFull.signal();
                } else if (writerClosed) {
                    return false;
                } else {
                    await();
                }
            }

            return true;
        }

        // the lock must be held
        private void advance(int size) {
            position += size;

            if (position == current.length) {
                current = null;
            }
        }

        private void await() throws IOException {
            try {
                notEmpty.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new InterruptedIOException("Interrupted while waiting for the pipe to be written");
            }
        }
    }
}
