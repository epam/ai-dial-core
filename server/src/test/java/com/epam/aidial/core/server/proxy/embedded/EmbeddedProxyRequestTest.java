package com.epam.aidial.core.server.proxy.embedded;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.HostDelegate;
import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.plugin.Response;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.proxy.ProxyResponse;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.json.JsonObject;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmbeddedProxyRequestTest {

    private static Vertx vertx;

    private final ProxyContext pass = mock(ProxyContext.class);
    // the attempts the pass registered
    private final List<VertxHostDelegate> attempts = new CopyOnWriteArrayList<>();
    private ContextInternal context;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    @BeforeEach
    void recordAttempts() {
        doAnswer(invocation -> attempts.add(invocation.getArgument(0))).when(pass).addHostDelegate(any());
        // the tree the chain parsed, as a first attempt finds it
        when(pass.getRequestTree()).thenReturn(ProxyUtil.MAPPER.createObjectNode());
    }

    @Test
    void testRetriedAttemptParsesTheBodyOffTheEventLoop() throws Exception {
        // an earlier attempt took the chain's tree
        when(pass.getRequestTree()).thenReturn(null);
        Proxy proxy = mock(Proxy.class);
        when(proxy.getTaskExecutor()).thenReturn(new AsyncTaskExecutor(vertx, new JsonObject()));
        when(pass.getProxy()).thenReturn(proxy);
        AtomicReference<Request> seen = new AtomicReference<>();
        AtomicReference<Context> calledOn = new AtomicReference<>();
        EmbeddedProxyRequest request = request((call, host) -> {
            seen.set(call);
            calledOn.set(Vertx.currentContext());
            return Future.succeededFuture(new Answer(stream(false)));
        });

        await(onContext(() -> request.send(Buffer.buffer("{\"model\":\"m\"}"))));

        assertEquals("m", seen.get().bodyTree().path("model").asText());
        assertSame(context, calledOn.get());
        verify(proxy).getTaskExecutor();
    }

    @Test
    void testPluginGetsThePathAndQuery() throws Exception {
        AtomicReference<Request> seen = new AtomicReference<>();

        await(request((call, host) -> {
            seen.set(call);
            return Future.succeededFuture(new Answer(stream(false)));
        }).send(Buffer.buffer("{}")));

        assertEquals("/openai/v1/responses?x=1", seen.get().uri());
        assertEquals(HttpMethod.POST, seen.get().method());
    }

    @Test
    void testThrowingPluginFailsTheSend() {
        assertSendFails((call, host) -> {
            throw new IllegalStateException("plugin defect");
        }, IllegalStateException.class);
    }

    @Test
    void testAnswerWithoutBodyFailsTheSend() throws Exception {
        // completed on the request's context, where a throw from the answer would be reported and swallowed
        EmbeddedProxyRequest request = request((call, host) -> context.succeededFuture(new Answer(null)));

        Future<ProxyResponse> sent = onContext(() -> request.send(Buffer.buffer("{}")));

        ExecutionException error = assertThrows(ExecutionException.class, () -> await(sent));
        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void testPluginMissingClassFailsTheSend() {
        assertSendFails((call, host) -> {
            throw new NoClassDefFoundError("com/example/Missing");
        }, NoClassDefFoundError.class);
    }

    @Test
    void testResetBeforeTheAnswerCancelsIt() {
        Promise<Response> pending = Promise.promise();
        EmbeddedProxyRequest request = request((call, host) -> pending.future());
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
        EmbeddedProxyRequest request = request((call, host) -> Future.succeededFuture(answer));
        await(request.send(Buffer.buffer("{}")));

        request.reset();
        request.reset();

        assertEquals(1, answer.cancelled.get());
    }

    @Test
    void testClientGoneBeforeTheSendCancelsAtOnce() throws Exception {
        HttpServerResponse closed = mock(HttpServerResponse.class);
        when(closed.closed()).thenReturn(true);
        AtomicReference<Future<Void>> cancelled = new AtomicReference<>();

        request((call, host) -> {
            cancelled.set(host.cancelled());
            return Promise.<Response>promise().future();
        }, closed).send(Buffer.buffer("{}"));

        await(cancelled.get());
    }

    @Test
    void testBodyReadsStreamHandedBackPaused() throws Exception {
        ProxyResponse response = await(request((call, host) -> Future.succeededFuture(new Answer(stream(true)))).send(Buffer.buffer("{}")));

        assertEquals("answer", await(response.body()).toString());
    }

    @Test
    void testSendTakesThePluginWhenSent() throws Exception {
        AtomicInteger taken = new AtomicInteger();
        OriginPlugin plugin = (call, host) -> Future.succeededFuture(new Answer(stream(false)));
        EmbeddedProxyRequest request = request(() -> {
            taken.incrementAndGet();
            return plugin;
        }, mock(HttpServerResponse.class));
        assertEquals(0, taken.get());

        await(request.send(Buffer.buffer("{}")));

        assertEquals(1, taken.get());
    }

    @Test
    void testPluginThatCannotBeTakenFailsTheSend() {
        EmbeddedProxyRequest request = request(() -> {
            throw new IllegalStateException("Core is shutting down");
        }, mock(HttpServerResponse.class));

        ExecutionException error = assertThrows(ExecutionException.class, () -> await(request.send(Buffer.buffer("{}"))));

        assertInstanceOf(IllegalStateException.class, error.getCause());
    }

    @Test
    void testClientGoneAndPluginFailedClosesEveryAttempt() {
        HttpServerResponse closed = mock(HttpServerResponse.class);
        when(closed.closed()).thenReturn(true);

        request((call, host) -> Future.failedFuture(new IllegalStateException("stopped")), closed).send(Buffer.buffer("{}"));

        verify(pass).closeHostDelegates();
    }

    // a router's decide or notice request

    @Test
    void testRouterRequestGetsTheGivenTreeAndLeavesTheContextsAlone() throws Exception {
        ObjectNode tree = ProxyUtil.MAPPER.createObjectNode().put("model", "r1");
        AtomicReference<Request> seen = new AtomicReference<>();

        await(routerRequest((call, host) -> {
            seen.set(call);
            return Future.succeededFuture(new Answer(stream(false)));
        }, new ApiKeyData(), tree).send(Buffer.buffer("{}")));

        assertSame(tree, seen.get().bodyTree());
        assertEquals("/decide", seen.get().uri());
        verify(pass, never()).getRequestTree();
        verify(pass, never()).setRequestTree(any());
    }

    @Test
    void testRouterRequestDoesNotWatchTheClient() throws Exception {
        HttpServerResponse client = mock(HttpServerResponse.class);
        when(client.closed()).thenReturn(true);

        ExecutionException error = assertThrows(ExecutionException.class, () -> await(routerRequest(
                (call, host) -> Future.failedFuture(new IllegalStateException("router failed")), new ApiKeyData(), null, client)
                .send(Buffer.buffer())));

        assertInstanceOf(IllegalStateException.class, error.getCause());
        verify(client, never()).closeHandler(any());
        verify(pass, never()).closeHostDelegates();
    }

    @Test
    void testRouterAttemptEndsOnceItsAnswerIsRead() throws Exception {
        AtomicReference<HostDelegate> kept = new AtomicReference<>();
        ProxyResponse response = await(routerRequest((call, host) -> {
            kept.set(host);
            return Future.succeededFuture(new Answer(stream(false)));
        }, new ApiKeyData(), null).send(Buffer.buffer()));

        assertEquals("answer", await(response.body()).toString());

        Response late = await(onContext(() -> kept.get().call(new Call("/openai/v1/chat/completions",
                MultiMap.caseInsensitiveMultiMap(), ProxyUtil.MAPPER.createObjectNode()))));
        assertEquals(401, late.statusCode());
        assertFalse(kept.get().cancelled().isComplete(), "an answered decision is not cancelled");
        assertEquals(1, attempts.size(), "the attempt stays registered with the pass, whose end closes it too");
    }

    @Test
    void testRouterAttemptEndsWhenThePluginFails() throws Exception {
        AtomicReference<HostDelegate> kept = new AtomicReference<>();

        assertThrows(ExecutionException.class, () -> await(routerRequest((call, host) -> {
            kept.set(host);
            return Future.failedFuture(new IllegalStateException("router failed"));
        }, new ApiKeyData(), null).send(Buffer.buffer())));

        Response late = await(onContext(() -> kept.get().call(new Call("/openai/v1/chat/completions",
                MultiMap.caseInsensitiveMultiMap(), ProxyUtil.MAPPER.createObjectNode()))));
        assertEquals(401, late.statusCode());
        assertFalse(kept.get().cancelled().isComplete());
    }

    @Test
    void testResetOfRouterRequestCancelsIt() throws Exception {
        AtomicReference<Future<Void>> cancelled = new AtomicReference<>();
        EmbeddedProxyRequest request = routerRequest((call, host) -> {
            cancelled.set(host.cancelled());
            return Promise.<Response>promise().future();
        }, new ApiKeyData(), null);
        request.send(Buffer.buffer());
        awaitCondition(() -> cancelled.get() != null);

        request.reset();

        await(cancelled.get());
    }

    private void assertSendFails(OriginPlugin plugin, Class<? extends Throwable> cause) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> await(request(plugin).send(Buffer.buffer("{}"))));
        assertInstanceOf(cause, error.getCause());
    }

    private EmbeddedProxyRequest request(OriginPlugin plugin) {
        return request(plugin, mock(HttpServerResponse.class));
    }

    private EmbeddedProxyRequest request(OriginPlugin plugin, HttpServerResponse client) {
        return request(() -> plugin, client);
    }

    private EmbeddedProxyRequest request(Supplier<OriginPlugin> plugin, HttpServerResponse client) {
        context = (ContextInternal) vertx.getOrCreateContext();
        when(pass.getResponse()).thenReturn(client);
        return new EmbeddedProxyRequest(context, pass, plugin, HttpMethod.POST, "plugin://p1", "plugin://p1/openai/v1/responses?x=1");
    }

    private EmbeddedProxyRequest routerRequest(OriginPlugin plugin, ApiKeyData callKey, ObjectNode tree) {
        return routerRequest(plugin, callKey, tree, mock(HttpServerResponse.class));
    }

    private EmbeddedProxyRequest routerRequest(OriginPlugin plugin, ApiKeyData callKey, ObjectNode tree, HttpServerResponse client) {
        context = (ContextInternal) vertx.getOrCreateContext();
        when(pass.getResponse()).thenReturn(client);
        return new EmbeddedProxyRequest(context, pass, () -> plugin, HttpMethod.POST,
                "plugin://p1", "plugin://p1/decide", new EmbeddedProxyRequest.RouterRequest(callKey, tree));
    }

    private <T> T onContext(Supplier<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            try {
                result.complete(action.get());
            } catch (Throwable e) {
                result.completeExceptionally(e);
            }
        });
        return result.get(5, TimeUnit.SECONDS);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met in 5 s");
            Thread.sleep(10);
        }
    }

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static OneChunkStream stream(boolean paused) {
        return new OneChunkStream(vertx.getOrCreateContext(), paused);
    }

    private static final class Answer implements Response {

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
