package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.plugin.Response;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddedServerResponseTest {

    private static Vertx vertx;

    private ContextInternal parent;
    private ContextInternal child;
    private EmbeddedServerResponse response;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    @BeforeEach
    void createResponse() {
        parent = (ContextInternal) vertx.getOrCreateContext();
        child = parent.duplicate();
        response = new EmbeddedServerResponse(child, parent);
    }

    @Test
    void testHeadFreezesOnTheFirstWrite() throws Exception {
        onChild(() -> {
            response.setStatusCode(201).putHeader("X-A", "1").write("a");
            return response.putHeader("X-B", "2");
        });

        Response answer = await(response.answer());
        assertEquals(201, answer.statusCode());
        assertEquals("1", answer.headers().get("X-A"));
        assertNull(answer.headers().get("X-B"));
        assertTrue(response.headWritten());
    }

    @Test
    void testEndWithoutWrite() throws Exception {
        onChild(() -> response.setStatusCode(404).end("missing"));

        Response answer = await(response.answer());
        assertEquals(404, answer.statusCode());
        assertEquals("missing", read(answer).get(5, TimeUnit.SECONDS));
        assertTrue(response.ended());
    }

    @Test
    void testNothingDroppedBeforeThePluginSetsHandler() throws Exception {
        onChild(() -> {
            response.write("a");
            response.write("b");
            return response.end("c");
        });

        // the plugin reads only after everything was written
        assertEquals("abc", read(await(response.answer())).get(5, TimeUnit.SECONDS));
    }

    @Test
    void testChunksReachThePluginWithItsContextCurrent() throws Exception {
        onChild(() -> response.write("a"));
        Response answer = await(response.answer());
        List<Context> seen = new CopyOnWriteArrayList<>();
        CompletableFuture<Void> ended = new CompletableFuture<>();
        onParent(() -> {
            answer.body().handler(chunk -> seen.add(Vertx.currentContext()));
            return answer.body().endHandler(ignored -> ended.complete(null));
        });

        onChild(() -> response.end("b"));

        ended.get(5, TimeUnit.SECONDS);
        assertEquals(2, seen.size());
        seen.forEach(context -> assertSame(parent, context));
    }

    @Test
    void testBackpressureAndDrainOnTheChildContext() throws Exception {
        onChild(() -> response.write("0"));
        Response answer = await(response.answer());
        List<Context> drained = new CopyOnWriteArrayList<>();
        boolean full = onChild(() -> {
            response.drainHandler(ignored -> drained.add(Vertx.currentContext()));
            while (!response.writeQueueFull()) {
                response.write("x");
            }
            return response.writeQueueFull();
        });
        assertTrue(full);

        onParent(() -> answer.body().handler(chunk -> { }));

        awaitCondition(() -> !drained.isEmpty());
        assertSame(child, drained.get(0));
        assertFalse(onChild(response::writeQueueFull));
    }

    @Test
    void testWriteFromVirtualThread() throws Exception {
        Thread writer = Thread.ofVirtual().start(() -> response.setStatusCode(500).end("failed"));
        writer.join(5000);

        Response answer = await(response.answer());
        assertEquals(500, answer.statusCode());
        assertEquals("failed", read(answer).get(5, TimeUnit.SECONDS));
        assertEquals(500, response.getStatusCode());
        assertTrue(response.ended());
    }

    @Test
    void testResetBeforeTheHeadFailsTheCall() {
        onChild(response::reset);

        ExecutionException error = assertThrows(ExecutionException.class, () -> await(response.answer()));
        assertInstanceOf(io.vertx.core.http.HttpClosedException.class, error.getCause());
        assertTrue(response.closed());
    }

    @Test
    void testResetAfterTheHeadFailsTheBody() throws Exception {
        onChild(() -> response.write("a"));
        Response answer = await(response.answer());

        onChild(response::reset);

        ExecutionException error = assertThrows(ExecutionException.class, () -> read(answer).get(5, TimeUnit.SECONDS));
        assertInstanceOf(io.vertx.core.http.HttpClosedException.class, error.getCause());
    }

    @Test
    void testCancelBeforeTheHeadFailsTheCall() {
        onParent(() -> {
            response.cancel();
            return null;
        });

        assertThrows(ExecutionException.class, () -> await(response.answer()));
        assertTrue(response.closed());
    }

    @Test
    void testCancelFailsAnUnreadBody() throws Exception {
        onChild(() -> response.write("a"));
        Response answer = await(response.answer());
        CompletableFuture<String> read = read(answer);
        onParent(() -> answer.body().pause());

        onParent(() -> {
            answer.cancel();
            return null;
        });

        ExecutionException error = assertThrows(ExecutionException.class, () -> read.get(5, TimeUnit.SECONDS));
        assertInstanceOf(io.vertx.core.http.HttpClosedException.class, error.getCause());
    }

    @Test
    void testCancelLeavesBodyReadToItsEnd() throws Exception {
        onChild(() -> response.end("all"));
        Response answer = await(response.answer());
        assertEquals("all", read(answer).get(5, TimeUnit.SECONDS));
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        onParent(() -> answer.body().exceptionHandler(errors::add));

        onParent(() -> {
            answer.cancel();
            return null;
        });
        onParent(() -> null);

        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void testCancelFailsThePipe() throws Exception {
        TestSource source = new TestSource(child);
        CompletableFuture<Void> piped = new CompletableFuture<>();
        onChild(() -> {
            response.setChunked(true);
            source.pipeTo(response).onComplete(result -> {
                if (result.succeeded()) {
                    piped.complete(null);
                } else {
                    piped.completeExceptionally(result.cause());
                }
            });
            source.emit("a");
            return null;
        });
        Response answer = await(response.answer());

        onParent(() -> {
            answer.cancel();
            return null;
        });
        onChild(() -> {
            source.emit("b");
            return null;
        });

        assertThrows(ExecutionException.class, () -> piped.get(5, TimeUnit.SECONDS));
        assertTrue(response.closed());
    }

    @Test
    void testCancelReleasesPausedPipe() throws Exception {
        onChild(() -> response.write("0"));
        Response answer = await(response.answer());
        List<Context> drained = new CopyOnWriteArrayList<>();
        onChild(() -> {
            response.drainHandler(ignored -> drained.add(Vertx.currentContext()));
            while (!response.writeQueueFull()) {
                response.write("x");
            }
            return null;
        });

        onParent(() -> {
            answer.cancel();
            return null;
        });

        awaitCondition(() -> !drained.isEmpty());
        Future<Void> late = onChild(() -> response.write("late"));
        assertThrows(ExecutionException.class, () -> await(late));
    }

    private <T> T onChild(Supplier<T> action) {
        return on(child, action);
    }

    private <T> T onParent(Supplier<T> action) {
        return on(parent, action);
    }

    private static <T> T on(Context context, Supplier<T> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                result.complete(action.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        try {
            return result.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CompletableFuture<String> read(Response answer) {
        CompletableFuture<String> result = new CompletableFuture<>();
        Buffer content = Buffer.buffer();
        onParent(() -> {
            ReadStream<Buffer> body = answer.body();
            body.exceptionHandler(result::completeExceptionally);
            body.endHandler(ignored -> result.complete(content.toString()));
            return body.handler(content::appendBuffer);
        });
        return result;
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void awaitCondition(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.get()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met in 5 s");
            Thread.sleep(10);
        }
    }

    /**
     * A source that emits what the test hands it, on the context it belongs to.
     */
    private static final class TestSource implements ReadStream<Buffer> {

        private final Context context;
        private Handler<Buffer> handler;

        TestSource(Context context) {
            this.context = context;
        }

        Future<Void> pipeTo(EmbeddedServerResponse response) {
            return pipe().endOnFailure(false).endOnSuccess(false).to(response);
        }

        void emit(String chunk) {
            assertSame(context, Vertx.currentContext());
            if (handler != null) {
                handler.handle(Buffer.buffer(chunk));
            }
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.handler = handler;
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            return this;
        }
    }
}
