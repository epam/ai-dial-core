package com.epam.aidial.core.server.vertx.stream;

import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;


class InputStreamAdapterTest {

    protected static final String TEXT = """
            Line1
            Line2
            Line3
            Line4
            Line5
            """;

    @Test
    void testReadOneChunk() throws Exception {
        TestReadStream source = new TestReadStream();
        InputStreamAdapter stream = new InputStreamAdapter(source);

        source.append(TEXT).end();
        Assertions.assertEquals(TEXT, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        Assertions.assertEquals(-1, stream.read());

        stream.close();
        Assertions.assertThrows(IOException.class, stream::read);
    }

    @Test
    void testReadManyChunks() throws Exception {
        TestReadStream source = new TestReadStream();
        InputStreamAdapter stream = new InputStreamAdapter(source);

        for (String chunk : TEXT.split("\n")) {
            source.append(chunk).append("\n");
        }

        source.end();
        Assertions.assertEquals(TEXT, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        Assertions.assertEquals(-1, stream.read());

        stream.close();
        Assertions.assertThrows(IOException.class, stream::read);
    }

    @Test
    void testReadChunkByChunk() throws Exception {
        TestReadStream source = new TestReadStream();
        InputStreamAdapter stream = new InputStreamAdapter(source);

        for (String chunk : TEXT.split("\n")) {
            source.append(chunk);
            byte[] bytes = new byte[chunk.length() + 2];
            int read = stream.read(bytes, 1, chunk.length());
            Assertions.assertEquals(chunk.length(), read);
            Assertions.assertEquals(chunk, new String(bytes, 1, chunk.length(), StandardCharsets.UTF_8));
        }

        source.end();
        Assertions.assertEquals(-1, stream.read());

        stream.close();
        Assertions.assertThrows(IOException.class, stream::read);
    }

    @Test
    void testBigFile() throws Exception {
        Path file = Files.createTempFile("input-stream-test", ".txt");
        String text = "1".repeat(64 * 1024 * 1024);
        Vertx vertx = Vertx.vertx();

        try {
            Files.writeString(file, text);

            CompletableFuture<AsyncFile> future = new CompletableFuture<>();
            vertx.fileSystem().open(file.toString(), new OpenOptions())
                    .onSuccess(future::complete)
                    .onFailure(future::completeExceptionally);

            AsyncFile source = future.get();
            InputStreamAdapter stream = new InputStreamAdapter(source);

            Assertions.assertEquals(text, new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        } finally {
            Files.deleteIfExists(file);
            vertx.close();
        }
    }

    @Test
    void testError() {
        TestReadStream source = new TestReadStream();
        InputStreamAdapter stream = new InputStreamAdapter(source);

        source.append(TEXT);
        source.error(new IllegalAccessError("NotAccess"));

        Assertions.assertThrows(IOException.class, stream::read);
    }

    @Test
    void testReadFailsWhenNoDataArrivesInTime() {
        TestReadStream source = new TestReadStream();
        InputStreamAdapter stream = new InputStreamAdapter(source, Duration.ofMillis(100));

        long start = System.nanoTime();
        InputStreamAdapter.StallTimeoutException e =
                Assertions.assertThrows(InputStreamAdapter.StallTimeoutException.class, stream::read);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();

        Assertions.assertTrue(elapsedMillis >= 90, "must wait roughly the full timeout, waited " + elapsedMillis + "ms");
        Assertions.assertTrue(e.getMessage().contains("100"), e.getMessage());
        // the adapter is now failed for good
        Assertions.assertThrows(IOException.class, stream::read);
    }

    @Test
    void testSlowButSteadyDataIsNotStalled() throws Exception {
        TestReadStream source = new TestReadStream();
        InputStreamAdapter stream = new InputStreamAdapter(source, Duration.ofMillis(300));

        CompletableFuture<byte[]> result = CompletableFuture.supplyAsync(() -> {
            try {
                return stream.readAllBytes();
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });

        for (String chunk : TEXT.split("\n")) {
            Thread.sleep(100);
            source.append(chunk).append("\n");
        }
        Thread.sleep(100);
        source.end();

        Assertions.assertEquals(TEXT, new String(result.get(5, TimeUnit.SECONDS), StandardCharsets.UTF_8));
    }

    @Test
    void testTimeIsNotCountedWhileTheReaderIsBusy() throws Exception {
        TestReadStream source = new TestReadStream();
        InputStreamAdapter stream = new InputStreamAdapter(source, Duration.ofMillis(200));
        source.append("first").end();

        // the reader is busy for longer than readTimeout between these two calls; no bytes are pending either time
        byte[] firstChunk = new byte[5];
        Assertions.assertEquals(5, stream.read(firstChunk, 0, 5));
        Thread.sleep(400);

        // the stream already ended, so this does not wait on the timeout at all; it proves the prior sleep did not
        // leave the adapter in a failed state
        Assertions.assertEquals(-1, stream.read());
    }

    private static class TestReadStream implements ReadStream<Buffer> {

        private Handler<Buffer> dataHandler;
        private Handler<Void> endHandler;
        private Handler<Throwable> errorHandler;

        TestReadStream append(String text) {
            dataHandler.handle(Buffer.buffer(text));
            return this;
        }

        TestReadStream end() {
            endHandler.handle(null);
            return this;
        }

        TestReadStream error(Throwable error) {
            errorHandler.handle(error);
            return this;
        }

        @Override
        public TestReadStream handler(Handler<Buffer> dataHandler) {
            this.dataHandler = dataHandler;
            return this;
        }

        @Override
        public TestReadStream endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }

        @Override
        public TestReadStream exceptionHandler(Handler<Throwable> handler) {
            this.errorHandler = handler;
            return this;
        }

        @Override
        public TestReadStream pause() {
            return this;
        }

        @Override
        public TestReadStream resume() {
            return this;
        }

        @Override
        public TestReadStream fetch(long amount) {
            return this;
        }
    }

}
