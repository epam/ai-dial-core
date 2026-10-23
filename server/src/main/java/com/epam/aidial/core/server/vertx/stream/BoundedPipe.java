package com.epam.aidial.core.server.vertx.stream;

import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Passes bytes from a writing thread to a reading thread through a ring buffer of a fixed size, so that a slow reader
 * blocks the writer instead of growing memory. Both ends block, so neither may run on an event loop. Use one writing thread.
 *
 * <p>The reader waits until the writer closes the output or calls {@link #closeExceptionally(Throwable)}, and sees a clean
 * end of the stream only after the former, so call the latter on a failure instead of closing.
 *
 * <p>A stalled or interrupted write, an interrupted read and {@link #closeExceptionally(Throwable)} fail the pipe: the pending
 * and all later reads and writes throw an {@link IOException} caused by the first failure. Closing the input fails them too.
 */
public class BoundedPipe {

    private final Duration stallTimeout;

    // guards all the state below, and publishes it between reads that run on different virtual threads
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition notEmpty = lock.newCondition();
    private final Condition notFull = lock.newCondition();
    private final byte[] buffer;
    private int head;
    private int count;
    private boolean writerClosed;
    private boolean readerClosed;
    private IOException failure;

    @Getter
    private final OutputStream output = new Output();
    @Getter
    private final InputStream input = new Input();

    /**
     * @param capacity     size of the buffer in bytes
     * @param stallTimeout how long a write waits for the reader to take a byte before it fails
     */
    public BoundedPipe(int capacity, Duration stallTimeout) {
        if (capacity < 1) {
            throw new IllegalArgumentException("Capacity must be positive: " + capacity);
        }
        if (stallTimeout.isZero() || stallTimeout.isNegative()) {
            throw new IllegalArgumentException("Stall timeout must be positive: " + stallTimeout);
        }

        this.buffer = new byte[capacity];
        this.stallTimeout = stallTimeout;
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

    // the lock must be held, returns when there is a free byte
    private void awaitFreeSpace() throws IOException {
        long remainingNanos = stallTimeout.toNanos();

        checkWritable();

        while (count == buffer.length) {
            if (remainingNanos <= 0) {
                throw fail(new IOException("Pipe is stalled, nothing was read for " + stallTimeout));
            }

            try {
                remainingNanos = notFull.awaitNanos(remainingNanos);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw fail(new InterruptedIOException("Interrupted while waiting for the pipe to be read"));
            }

            checkWritable();
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
        head = 0;
        count = 0;
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
            lock.lock();
            try {
                awaitFreeSpace();

                buffer[(head + count) % buffer.length] = (byte) b;
                count++;
                notEmpty.signalAll();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);

            lock.lock();
            try {
                while (length > 0) {
                    awaitFreeSpace();

                    int size = Math.min(length, buffer.length - count);
                    int tail = (head + count) % buffer.length;
                    int beforeWrap = Math.min(size, buffer.length - tail);
                    System.arraycopy(bytes, offset, buffer, tail, beforeWrap);
                    System.arraycopy(bytes, offset + beforeWrap, buffer, 0, size - beforeWrap);

                    count += size;
                    offset += size;
                    length -= size;
                    notEmpty.signalAll();
                }
            } finally {
                lock.unlock();
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
                if (isEndOfStream()) {
                    return -1;
                }

                int value = buffer[head] & 0xFF;
                head = (head + 1) % buffer.length;
                count--;
                notFull.signalAll();
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
                if (isEndOfStream()) {
                    return -1;
                }

                int size = Math.min(length, count);
                int beforeWrap = Math.min(size, buffer.length - head);
                System.arraycopy(buffer, head, bytes, offset, beforeWrap);
                System.arraycopy(buffer, 0, bytes, offset + beforeWrap, size - beforeWrap);

                head = (head + size) % buffer.length;
                count -= size;
                notFull.signalAll();
                return size;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int available() throws IOException {
            lock.lock();
            try {
                checkReadable();
                return count;
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

        // the lock must be held, waits for a byte and returns true if there will be none
        private boolean isEndOfStream() throws IOException {
            while (count == 0) {
                checkReadable();

                if (writerClosed) {
                    return true;
                }

                try {
                    notEmpty.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw fail(new InterruptedIOException("Interrupted while waiting for the pipe to be written"));
                }
            }

            return false;
        }
    }
}
