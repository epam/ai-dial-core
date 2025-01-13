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
import java.util.concurrent.CompletableFuture;


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
