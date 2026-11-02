package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.OriginRequest;
import com.epam.aidial.core.plugin.OriginResponse;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.streams.ReadStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EmbeddedProxyRequestTest {

    private static Vertx vertx;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    @Test
    void testPluginGetsThePathAndQuery() throws Exception {
        AtomicReference<OriginRequest> seen = new AtomicReference<>();

        await(request(call -> {
            seen.set(call);
            return Future.succeededFuture(new Answer(stream(false)));
        }).send(Buffer.buffer("{}")));

        assertEquals("/openai/v1/responses?x=1", seen.get().uri());
        assertEquals(HttpMethod.POST, seen.get().method());
    }

    @Test
    void testThrowingPluginFailsTheSend() {
        assertSendFails(call -> {
            throw new IllegalStateException("plugin defect");
        }, IllegalStateException.class);
    }

    @Test
    void testPluginMissingClassFailsTheSend() {
        assertSendFails(call -> {
            throw new NoClassDefFoundError("com/example/Missing");
        }, NoClassDefFoundError.class);
    }

    @Test
    void testResetBeforeTheAnswerCancelsIt() {
        Promise<OriginResponse> pending = Promise.promise();
        EmbeddedProxyRequest request = request(call -> pending.future());
        Future<ProxyResponse> sent = request.send(Buffer.buffer("{}"));

        request.reset();
        Answer answer = new Answer(stream(false));
        pending.complete(answer);

        assertThrows(ExecutionException.class, () -> await(sent));
        assertEquals(1, answer.cancelled.get());
    }

    @Test
    void testResetAfterTheAnswerCancelsItOnce() throws Exception {
        Answer answer = new Answer(stream(false));
        EmbeddedProxyRequest request = request(call -> Future.succeededFuture(answer));
        await(request.send(Buffer.buffer("{}")));

        request.reset();
        request.reset();

        assertEquals(1, answer.cancelled.get());
    }

    @Test
    void testBodyReadsStreamHandedBackPaused() throws Exception {
        ProxyResponse response = await(request(call -> Future.succeededFuture(new Answer(stream(true)))).send(Buffer.buffer("{}")));

        assertEquals("answer", await(response.body()).toString());
    }

    private static void assertSendFails(OriginPlugin plugin, Class<? extends Throwable> cause) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(request(plugin).send(Buffer.buffer("{}"))));
        assertInstanceOf(cause, error.getCause());
    }

    private static EmbeddedProxyRequest request(OriginPlugin plugin) {
        ContextInternal context = (ContextInternal) vertx.getOrCreateContext();
        return new EmbeddedProxyRequest(context, plugin, HttpMethod.POST, "plugin://p1", "plugin://p1/openai/v1/responses?x=1");
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static OneChunkStream stream(boolean paused) {
        return new OneChunkStream(vertx.getOrCreateContext(), paused);
    }

    private static final class Answer implements OriginResponse {

        private final ReadStream<Buffer> body;
        private final AtomicInteger cancelled = new AtomicInteger();

        Answer(ReadStream<Buffer> body) {
            this.body = body;
        }

        @Override
        public int statusCode() {
            return 200;
        }

        @Override
        public MultiMap headers() {
            return MultiMap.caseInsensitiveMultiMap();
        }

        @Override
        public ReadStream<Buffer> body() {
            return body;
        }

        @Override
        public void cancel() {
            cancelled.incrementAndGet();
        }
    }

    /**
     * Emits {@code answer} and ends, from a task of its own, once it flows: at once, or after a resume when it starts
     * paused.
     */
    private static final class OneChunkStream implements ReadStream<Buffer> {

        private final Context context;
        private boolean paused;
        private boolean done;
        private Handler<Buffer> handler;
        private Handler<Void> endHandler;

        OneChunkStream(Context context, boolean paused) {
            this.context = context;
            this.paused = paused;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.handler = handler;
            context.runOnContext(ignored -> emit());
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            paused = true;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            paused = false;
            context.runOnContext(ignored -> emit());
            return this;
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            return resume();
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            return this;
        }

        private void emit() {
            if (paused || done || handler == null) {
                return;
            }
            done = true;
            handler.handle(Buffer.buffer("answer"));
            if (endHandler != null) {
                endHandler.handle(null);
            }
        }
    }
}
