package com.epam.aidial.core.server.vertx.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

@Timeout(60)
class BoundedPipeTest {

    private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);

    private final List<Thread> threads = new ArrayList<>();

    @AfterEach
    void tearDown() throws InterruptedException {
        for (Thread thread : threads) {
            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(10));
            Assertions.assertFalse(thread.isAlive(), "the thread should finish");
        }
    }

    @Test
    void testRoundTrip() throws IOException {
        BoundedPipe pipe = new BoundedPipe(8, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1, 2, 3});
        pipe.getOutput().write(new byte[] {9, 4, 5, 6, 9}, 1, 3);
        pipe.getOutput().write(7);
        pipe.getOutput().close();

        Assertions.assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6, 7}, pipe.getInput().readAllBytes());
    }

    @Test
    void testPartialReadsAndEndIsSticky() throws IOException {
        BoundedPipe pipe = new BoundedPipe(8, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {10, 20, 30, 40, (byte) 200});
        pipe.getOutput().close();

        InputStream input = pipe.getInput();
        byte[] bytes = new byte[2];
        Assertions.assertEquals(2, input.read(bytes));
        Assertions.assertArrayEquals(new byte[] {10, 20}, bytes);
        Assertions.assertEquals(30, input.read());
        Assertions.assertEquals(2, input.read(bytes));
        Assertions.assertArrayEquals(new byte[] {40, (byte) 200}, bytes);
        Assertions.assertEquals(-1, input.read());
        Assertions.assertEquals(-1, input.read(bytes));
    }

    @Test
    void testEmptyWriteAndEmptyRead() throws IOException {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[0]);
        pipe.getOutput().write(new byte[] {1}, 1, 0);
        pipe.getOutput().close();

        Assertions.assertEquals(0, pipe.getInput().read(new byte[4], 0, 0));
        Assertions.assertEquals(-1, pipe.getInput().read());
    }

    @Test
    void testInvalidRange() {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);

        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> pipe.getOutput().write(new byte[2], 1, 2));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> pipe.getOutput().write(new byte[2], -1, 1));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> pipe.getInput().read(new byte[2], 1, 2));
        Assertions.assertThrows(IndexOutOfBoundsException.class, () -> pipe.getInput().read(new byte[2], 0, -1));
    }

    @Test
    void testInvalidArguments() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new BoundedPipe(0, LONG_TIMEOUT));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new BoundedPipe(2, Duration.ZERO));
        Assertions.assertThrows(IllegalArgumentException.class, () -> new BoundedPipe(2, Duration.ofSeconds(-1)));
    }

    @Test
    void testWriterBlocksWhenFullUntilByteIsRead() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().write(new byte[] {2});

        CompletableFuture<Void> third = new CompletableFuture<>();
        Thread writer = startThread(() -> pipe.getOutput().write(new byte[] {3}), third);
        awaitParked(writer);
        Assertions.assertFalse(third.isDone());

        Assertions.assertEquals(1, pipe.getInput().read());
        third.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(2, pipe.getInput().read());
        Assertions.assertEquals(3, pipe.getInput().read());
    }

    @Test
    void testUseAfterInputIsClosed() throws IOException {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getInput().close();
        pipe.getInput().close();

        Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(new byte[] {2}));
        Assertions.assertThrows(IOException.class, () -> pipe.getInput().read());
    }

    @Test
    void testClosingInputFailsParkedWriter() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().write(new byte[] {2});

        CompletableFuture<Void> third = new CompletableFuture<>();
        Thread writer = startThread(() -> pipe.getOutput().write(new byte[] {3}), third);
        awaitParked(writer);

        pipe.getInput().close();

        assertFailsWith(IOException.class, "Input is closed", third);
    }

    @Test
    void testClosingInputFailsParkedReader() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);

        CompletableFuture<Void> read = new CompletableFuture<>();
        Thread reader = startThread(() -> pipe.getInput().read(), read);
        awaitParked(reader);

        pipe.getInput().close();

        assertFailsWith(IOException.class, "Input is closed", read);
    }

    @Test
    void testUseAfterOutputIsClosed() throws IOException {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().close();
        pipe.getOutput().close();

        Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(new byte[] {2}));
        Assertions.assertEquals(1, pipe.getInput().read());
        Assertions.assertEquals(-1, pipe.getInput().read());
    }

    @Test
    void testInterruptedWriter() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().write(new byte[] {2});

        CompletableFuture<Void> third = new CompletableFuture<>();
        Thread writer = startThread(() -> pipe.getOutput().write(new byte[] {3}), third);
        awaitParked(writer);

        writer.interrupt();

        assertFailsWith(InterruptedIOException.class, "Interrupted", third);
    }

    @Test
    void testInterruptedReader() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);

        CompletableFuture<Void> read = new CompletableFuture<>();
        Thread reader = startThread(() -> pipe.getInput().read(), read);
        awaitParked(reader);

        reader.interrupt();

        assertFailsWith(InterruptedIOException.class, "Interrupted", read);
    }

    @Test
    void testSingleByteReadReturnsUnsignedValues() throws IOException {
        BoundedPipe pipe = new BoundedPipe(4, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {(byte) 200, (byte) 255, 0});
        pipe.getOutput().close();

        InputStream input = pipe.getInput();
        Assertions.assertEquals(200, input.read());
        Assertions.assertEquals(255, input.read());
        Assertions.assertEquals(0, input.read());
        Assertions.assertEquals(-1, input.read());
    }

    @Test
    void testCapacityOfOneBlocksTheSecondWrite() throws Exception {
        BoundedPipe pipe = new BoundedPipe(1, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});

        CompletableFuture<Void> second = new CompletableFuture<>();
        Thread writer = startThread(() -> pipe.getOutput().write(new byte[] {2}), second);
        awaitParked(writer);
        Assertions.assertFalse(second.isDone());

        Assertions.assertEquals(1, pipe.getInput().read());
        second.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(2, pipe.getInput().read());
    }

    @Test
    void testWriteLargerThanCapacityCompletesAsTheReaderDrains() throws Exception {
        BoundedPipe pipe = new BoundedPipe(4, LONG_TIMEOUT);
        byte[] bytes = sequence(0, 10);

        CompletableFuture<Void> written = new CompletableFuture<>();
        Thread writer = startThread(() -> {
            pipe.getOutput().write(bytes);
            pipe.getOutput().close();
        }, written);
        awaitParked(writer);
        Assertions.assertFalse(written.isDone());

        Assertions.assertArrayEquals(bytes, pipe.getInput().readAllBytes());
        written.get(10, TimeUnit.SECONDS);
    }

    @Test
    void testReadingOneByteLetsTheBlockedWriteProgress() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1, 2});

        CompletableFuture<Void> written = new CompletableFuture<>();
        Thread writer = startThread(() -> pipe.getOutput().write(new byte[] {3, 4}), written);
        awaitParked(writer);

        Assertions.assertEquals(1, pipe.getInput().read());
        Assertions.assertEquals(2, pipe.getInput().read());
        written.get(10, TimeUnit.SECONDS);
        Assertions.assertEquals(3, pipe.getInput().read());
        Assertions.assertEquals(4, pipe.getInput().read());
    }

    @Test
    void testWrappedBufferKeepsOrderAndContent() throws IOException {
        int capacity = 5;
        BoundedPipe pipe = new BoundedPipe(capacity, LONG_TIMEOUT);
        int written = 0;
        int read = 0;

        for (int i = 0; i < 200; i++) {
            int toWrite = Math.min(1 + i % 4, capacity - (written - read));
            pipe.getOutput().write(sequence(written, toWrite));
            written += toWrite;

            byte[] bytes = new byte[8];
            int size = pipe.getInput().read(bytes, 1, Math.min(1 + i * 3 % 5, written - read));
            Assertions.assertArrayEquals(sequence(read, size), Arrays.copyOfRange(bytes, 1, 1 + size));
            read += size;
        }

        pipe.getOutput().close();
        Assertions.assertArrayEquals(sequence(read, written - read), pipe.getInput().readAllBytes());
    }

    @Test
    void testSingleByteWritesAndReadsWrapAround() throws IOException {
        BoundedPipe pipe = new BoundedPipe(3, LONG_TIMEOUT);

        for (int i = 0; i < 100; i++) {
            pipe.getOutput().write(i);
            pipe.getOutput().write(i + 1);
            Assertions.assertEquals(i & 0xFF, pipe.getInput().read());
            Assertions.assertEquals((i + 1) & 0xFF, pipe.getInput().read());
        }
    }

    @Test
    void testReadsProgressResetTheStallClock() throws Exception {
        BoundedPipe pipe = new BoundedPipe(1, Duration.ofSeconds(1));
        pipe.getOutput().write(0);

        CompletableFuture<Void> written = new CompletableFuture<>();
        startThread(() -> pipe.getOutput().write(new byte[] {1, 2, 3}), written);

        // the write takes longer than the stall timeout in total, but the reader never makes it wait that long at once
        for (int i = 0; i < 4; i++) {
            Thread.sleep(400);
            Assertions.assertEquals(i, pipe.getInput().read());
        }
        written.get(10, TimeUnit.SECONDS);
    }

    @Test
    void testStalledLargeWriteDiscardsThePartialBytes() {
        BoundedPipe pipe = new BoundedPipe(2, Duration.ofMillis(100));

        IOException stalled = Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(sequence(0, 5)));
        Assertions.assertTrue(stalled.getMessage().contains("stalled"), stalled.getMessage());

        IOException read = Assertions.assertThrows(IOException.class, () -> pipe.getInput().read());
        Assertions.assertTrue(read.getMessage().contains("stalled"), read.getMessage());
    }

    @Test
    void testStalledWritePoisonsTheWriterAndTheReader() throws IOException {
        BoundedPipe pipe = new BoundedPipe(2, Duration.ofMillis(100));
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().write(new byte[] {2});

        IOException stalled = Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(new byte[] {3}));
        Assertions.assertTrue(stalled.getMessage().contains("stalled"), stalled.getMessage());

        // a later write must not succeed and leave a hole in the stream
        IOException again = Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(new byte[] {4}));
        Assertions.assertTrue(again.getMessage().contains("stalled"), again.getMessage());
        Assertions.assertSame(stalled, again.getCause());

        // closing does not turn the failure into a clean end
        Assertions.assertDoesNotThrow(() -> pipe.getOutput().close());
        IOException read = Assertions.assertThrows(IOException.class, () -> pipe.getInput().read());
        Assertions.assertTrue(read.getMessage().contains("stalled"), read.getMessage());
        Assertions.assertThrows(IOException.class, () -> pipe.getInput().readAllBytes());
    }

    @Test
    void testInterruptedWritePoisonsTheReader() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().write(new byte[] {2});

        CompletableFuture<Void> third = new CompletableFuture<>();
        Thread writer = startThread(() -> pipe.getOutput().write(new byte[] {3}), third);
        awaitParked(writer);
        writer.interrupt();
        assertFailsWith(InterruptedIOException.class, "Interrupted", third);

        Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(new byte[] {4}));
        Assertions.assertDoesNotThrow(() -> pipe.getOutput().close());
        IOException read = Assertions.assertThrows(IOException.class, () -> pipe.getInput().read());
        Assertions.assertInstanceOf(InterruptedIOException.class, read.getCause());
    }

    @Test
    void testInterruptedReadPoisonsTheWriterAndTheReader() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);

        CompletableFuture<Void> read = new CompletableFuture<>();
        Thread reader = startThread(() -> pipe.getInput().read(), read);
        awaitParked(reader);
        reader.interrupt();
        assertFailsWith(InterruptedIOException.class, "Interrupted", read);

        IOException write = Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(1));
        Assertions.assertInstanceOf(InterruptedIOException.class, write.getCause());
        Assertions.assertDoesNotThrow(() -> pipe.getOutput().close());
        IOException retried = Assertions.assertThrows(IOException.class, () -> pipe.getInput().read());
        Assertions.assertInstanceOf(InterruptedIOException.class, retried.getCause());
    }

    @Test
    void testCloseOutputIsIdempotentAndDoesNotWaitForFullQueue() throws IOException {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().write(new byte[] {2});

        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            pipe.getOutput().close();
            pipe.getOutput().close();
        });

        Assertions.assertArrayEquals(new byte[] {1, 2}, pipe.getInput().readAllBytes());
    }

    @Test
    void testCloseOutputAfterInputIsClosedDoesNotThrow() throws IOException {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getInput().close();

        Assertions.assertDoesNotThrow(() -> pipe.getOutput().close());
        Assertions.assertDoesNotThrow(() -> pipe.getOutput().close());
    }

    @Test
    void testCloseExceptionallyFailsParkedReader() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);

        CompletableFuture<Void> read = new CompletableFuture<>();
        Thread reader = startThread(() -> pipe.getInput().read(), read);
        awaitParked(reader);

        RuntimeException cause = new RuntimeException("boom");
        pipe.closeExceptionally(cause);

        assertFailsWith(IOException.class, "aborted", read);
        ExecutionException e = Assertions.assertThrows(ExecutionException.class, read::get);
        Assertions.assertSame(cause, e.getCause().getCause().getCause());
    }

    @Test
    void testCloseExceptionallyFailsParkedWriter() throws Exception {
        BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1});
        pipe.getOutput().write(new byte[] {2});

        CompletableFuture<Void> third = new CompletableFuture<>();
        Thread writer = startThread(() -> pipe.getOutput().write(new byte[] {3}), third);
        awaitParked(writer);

        pipe.closeExceptionally(new RuntimeException("boom"));

        assertFailsWith(IOException.class, "aborted", third);
    }

    @Test
    void testCloseExceptionallyDiscardsDataAndTheFirstFailureWins() throws IOException {
        BoundedPipe pipe = new BoundedPipe(4, LONG_TIMEOUT);
        pipe.getOutput().write(new byte[] {1, 2, 3});

        RuntimeException first = new RuntimeException("first");
        pipe.closeExceptionally(first);
        pipe.closeExceptionally(new RuntimeException("second"));
        pipe.getOutput().close();

        IOException e = Assertions.assertThrows(IOException.class, () -> pipe.getInput().read());
        Assertions.assertSame(first, e.getCause().getCause());
        Assertions.assertThrows(IOException.class, () -> pipe.getInput().read());
        Assertions.assertThrows(IOException.class, () -> pipe.getOutput().write(new byte[] {4}));
    }

    @Test
    void testClosingInputWhileWriterAndReaderAreRunning() throws Exception {
        for (int iteration = 0; iteration < 100; iteration++) {
            BoundedPipe pipe = new BoundedPipe(2, LONG_TIMEOUT);
            AtomicInteger written = new AtomicInteger();

            CompletableFuture<Void> writing = new CompletableFuture<>();
            startThread(() -> {
                while (true) {
                    pipe.getOutput().write(new byte[] {1});
                    written.incrementAndGet();
                }
            }, writing);

            CompletableFuture<Void> reading = new CompletableFuture<>();
            startThread(() -> {
                while (pipe.getInput().read() >= 0) {
                    Thread.onSpinWait();
                }
            }, reading);

            int minimum = iteration % 7;
            awaitCondition(() -> written.get() > minimum, () -> "the writer should make progress");
            pipe.getInput().close();

            assertFailsWith(IOException.class, "closed", writing);
            assertFailsWith(IOException.class, "closed", reading);
        }
    }

    @Test
    void testReadsOnDifferentVirtualThreadsKeepTheOrder() throws Exception {
        int count = 200;
        BoundedPipe pipe = new BoundedPipe(4, LONG_TIMEOUT);
        CompletableFuture<Void> written = new CompletableFuture<>();
        startThread(() -> {
            for (int i = 0; i < count; i++) {
                pipe.getOutput().write(new byte[] {(byte) i, (byte) i});
            }
            pipe.getOutput().close();
            written.complete(null);
        }, written);

        ByteArrayOutputStream received = new ByteArrayOutputStream();
        while (true) {
            CompletableFuture<Integer> next = new CompletableFuture<>();
            // like InputStreamReader: every read is a new task and may run on another thread
            Thread.ofVirtual().start(() -> {
                try {
                    byte[] bytes = new byte[3];
                    int size = pipe.getInput().read(bytes);
                    if (size > 0) {
                        received.write(bytes, 0, size);
                    }
                    next.complete(size);
                } catch (Throwable e) {
                    next.completeExceptionally(e);
                }
            });
            if (next.get(10, TimeUnit.SECONDS) < 0) {
                break;
            }
        }

        written.get(10, TimeUnit.SECONDS);
        byte[] expected = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            expected[2 * i] = (byte) i;
            expected[2 * i + 1] = (byte) i;
        }
        Assertions.assertArrayEquals(expected, received.toByteArray());
    }

    private interface Task {
        void run() throws Exception;
    }

    private static byte[] sequence(int start, int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) (start + i);
        }
        return bytes;
    }

    private Thread startThread(Task task, CompletableFuture<Void> result) {
        Thread thread = new Thread(() -> {
            try {
                task.run();
                result.complete(null);
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        threads.add(thread);
        thread.start();
        return thread;
    }

    private static void assertFailsWith(Class<? extends Throwable> type, String message, CompletableFuture<?> result) {
        ExecutionException e = Assertions.assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        Assertions.assertInstanceOf(type, e.getCause());
        Assertions.assertTrue(e.getCause().getMessage().contains(message), e.getCause().getMessage());
    }

    private static void awaitParked(Thread thread) throws InterruptedException {
        awaitCondition(() -> isParked(thread), () -> "the thread should park, state=" + thread.getState());
    }

    private static boolean isParked(Thread thread) {
        return thread != null && (thread.getState() == Thread.State.WAITING || thread.getState() == Thread.State.TIMED_WAITING);
    }

    private static void awaitCondition(BooleanSupplier condition, Supplier<String> message) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            Assertions.assertTrue(System.nanoTime() < deadline, message);
            Thread.sleep(5);
        }
    }
}
