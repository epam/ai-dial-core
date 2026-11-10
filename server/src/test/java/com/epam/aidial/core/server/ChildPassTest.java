package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.plugin.OriginCall;
import com.epam.aidial.core.plugin.OriginResponse;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.impl.ContextInternal;
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
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChildPassTest {

    private static Vertx vertx;

    private ContextInternal pluginContext;
    private Proxy proxy;
    private ProxyContext parent;
    private final List<ProxyContext> dispatched = new CopyOnWriteArrayList<>();
    private ChildPass host;

    @BeforeAll
    static void startVertx() {
        vertx = Vertx.vertx();
    }

    @AfterAll
    static void stopVertx() {
        vertx.close();
    }

    @BeforeEach
    void createHost() {
        pluginContext = (ContextInternal) vertx.getOrCreateContext();
        proxy = mock(Proxy.class);
        when(proxy.dispatch(any())).thenAnswer(invocation -> {
            dispatched.add(invocation.getArgument(0));
            return Future.succeededFuture();
        });
        ApiKeyData key = new ApiKeyData();
        key.setOriginalKey(new Key());
        key.setPerRequestKey("per-request-key");
        key.setTraceId("trace-1");
        key.setSpanId("span-1");
        parent = mock(ProxyContext.class);
        when(parent.getProxyApiKeyData()).thenReturn(key);
        when(parent.getTraceFlags()).thenReturn("01");
        host = new ChildPass(proxy, parent, pluginContext);
    }

    @Test
    void testCallOutsideTheCreateRoutesFails() {
        ExecutionException error = assertThrows(ExecutionException.class,
                () -> await(call("/v1/ops/resource/share/list", body())));

        assertInstanceOf(IllegalArgumentException.class, error.getCause());
        verify(proxy, never()).dispatch(any());
    }

    @Test
    void testBackgroundResponsesCallFails() {
        ObjectNode body = body().put("background", true);

        assertThrows(ExecutionException.class, () -> await(call("/openai/v1/responses", body)));
        verify(proxy, never()).dispatch(any());
    }

    @Test
    void testCreateRoutesDispatched() throws Exception {
        for (String uri : List.of("/openai/deployments/m/chat/completions?api-version=1", "/openai/deployments/m/embeddings",
                "/openai/v1/chat/completions", "/openai/v1/responses", "/openai/responses", "/anthropic/v1/messages")) {
            call(uri, body());
        }

        awaitCondition(() -> dispatched.size() == 6);
        ProxyContext child = dispatched.get(0);
        assertTrue(child.isChildPass());
        assertEquals("trace-1", child.getTraceId());
        assertEquals("span-1", child.getParentSpanId());
        assertEquals(16, child.getSpanId().length());
        assertNotEquals(dispatched.get(1).getSpanId(), child.getSpanId());
        assertEquals("/openai/deployments/m/chat/completions", child.getRequest().path());
    }

    @Test
    void testCallAfterCloseAnswers401() throws Exception {
        host.close();

        OriginResponse answer = await(call("/openai/v1/chat/completions", body()));

        assertEquals(401, answer.statusCode());
        verify(proxy, never()).dispatch(any());
    }

    @Test
    void testRouterHostCallsCarryItsKey() throws Exception {
        ApiKeyData decideKey = new ApiKeyData();
        decideKey.setOriginalKey(new Key());
        decideKey.setPerRequestKey("decide-key");
        decideKey.setTraceId("trace-2");
        decideKey.setSpanId("span-2");
        host = ChildPass.forRouter(proxy, parent, pluginContext, decideKey);

        call("/openai/v1/chat/completions", body());

        awaitCondition(() -> dispatched.size() == 1);
        ProxyContext child = dispatched.get(0);
        assertEquals("trace-2", child.getTraceId());
        assertEquals("span-2", child.getParentSpanId());
    }

    @Test
    void testRouterHostWithoutKeyIsClosed() throws Exception {
        host = ChildPass.forRouter(proxy, parent, pluginContext, null);

        OriginResponse answer = await(call("/openai/v1/chat/completions", body()));

        assertEquals(401, answer.statusCode());
        verify(proxy, never()).dispatch(any());
    }

    @Test
    void testCancelCompletesCancelled() throws Exception {
        host.cancel();

        await(host.cancelled());
        assertEquals(401, await(call("/openai/v1/chat/completions", body())).statusCode());
    }

    @Test
    void testCloseLeavesCancelledPending() throws Exception {
        host.close();
        // a call after close runs after the close on the plugin's context
        await(call("/openai/v1/chat/completions", body()));

        assertFalse(host.cancelled().isComplete());
    }

    @Test
    void testPendingCallFailsOnClose() {
        Future<OriginResponse> pending = call("/openai/v1/chat/completions", body());
        awaitConditionQuietly(() -> dispatched.size() == 1);

        host.close();

        assertThrows(ExecutionException.class, () -> await(pending));
    }

    @Test
    void testChildStoppedOnClose() throws Exception {
        call("/openai/v1/chat/completions", body());
        awaitCondition(() -> dispatched.size() == 1);

        host.close();
        ProxyContext child = dispatched.get(0);
        on(child, () -> child.getResponse().setChunked(true).write(Buffer.buffer("late")));

        awaitCondition(() -> child.getResponse().closed());
        Future<Void> next = on(child, () -> child.getResponse().write(Buffer.buffer("more")));
        assertThrows(ExecutionException.class, () -> await(next));
    }

    @Test
    void testWhenClosedRunsOnceOnClose() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        host.whenClosed(runs::incrementAndGet);
        assertEquals(0, runs.get());

        host.close();
        host.cancel();

        awaitCondition(() -> runs.get() == 1);
        Thread.sleep(50);
        assertEquals(1, runs.get());
    }

    @Test
    void testWhenClosedRunsOnCancel() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        host.whenClosed(runs::incrementAndGet);

        host.cancel();

        awaitCondition(() -> runs.get() == 1);
    }

    @Test
    void testWhenClosedRunsAtOnceOnClosedPass() throws Exception {
        host.close();
        AtomicInteger runs = new AtomicInteger();

        host.whenClosed(runs::incrementAndGet);

        awaitCondition(() -> runs.get() == 1);
    }

    private Future<OriginResponse> call(String uri, ObjectNode body) {
        return on(pluginContext, () -> host.call(new OriginCall(uri, MultiMap.caseInsensitiveMultiMap(), body)));
    }

    private static ObjectNode body() {
        return ProxyUtil.MAPPER.createObjectNode().put("model", "m");
    }

    private static <T> T on(ProxyContext child, Supplier<T> action) {
        // a context of its own: the in-memory response takes writes from any thread, as a pass on a virtual thread makes
        return on(vertx.getOrCreateContext(), action);
    }

    private static <T> T on(io.vertx.core.Context context, Supplier<T> action) {
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

    private static <T> T await(Future<T> future) throws Exception {
        return future.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    private static void awaitConditionQuietly(Supplier<Boolean> condition) {
        try {
            awaitCondition(condition);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitCondition(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.get()) {
            assertTrue(System.currentTimeMillis() < deadline, "condition not met in 5 s");
            Thread.sleep(10);
        }
    }
}
