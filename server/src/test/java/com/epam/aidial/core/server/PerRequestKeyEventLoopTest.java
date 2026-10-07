package com.epam.aidial.core.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.Test;
import org.redisson.client.NettyHook;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Per-request API key assignment does blocking Redis calls ({@code ApiKeyStore.assignPerRequestApiKey}),
 * so it must never run on the event loop. Redis writes are slowed down by {@link SlowRedisHook} while a
 * request is in flight, and the Vert.x blocked-thread checker records every event-loop thread stuck in a Redis call longer
 * than {@link #MAX_EVENT_LOOP_EXECUTE_MS}.
 */
public class PerRequestKeyEventLoopTest extends ResourceBaseTest {

    private static final long REDIS_WRITE_DELAY_MS = 300;
    private static final long MAX_EVENT_LOOP_EXECUTE_MS = 200;

    private static final String CHAT_BODY = "{\"model\":\"gpt-3-turbo\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"gpt-3-turbo\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    private final List<String> eventLoopBlocks = new CopyOnWriteArrayList<>();

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject()
                .put("vertx", new JsonObject()
                        .put("blockedThreadCheckInterval", 50)
                        .put("maxEventLoopExecuteTime", MAX_EVENT_LOOP_EXECUTE_MS)
                        .put("maxEventLoopExecuteTimeUnit", TimeUnit.MILLISECONDS.name()))
                .put("redis", new JsonObject()
                        .put("nettyHook", new JsonObject().put("class", SlowRedisHook.class.getName())));
    }

    @Test
    void legacyChatCompletionsDoesNotBlockEventLoop() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request ->
                    TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json"));

            sendWithSlowRedis(HttpMethod.POST, "/openai/deployments/gpt-3-turbo/chat/completions", CHAT_BODY);
        }
        assertEventLoopNotBlocked();
    }

    @Test
    void openAiV1ChatCompletionsDoesNotBlockEventLoop() {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/chat/completions", request ->
                    TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json"));

            sendWithSlowRedis(HttpMethod.POST, "/openai/v1/chat/completions", CHAT_BODY);
        }
        assertEventLoopNotBlocked();
    }

    @Test
    void anthropicMessagesDoesNotBlockEventLoop() {
        String answer = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-ns\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/anthropic/v1/messages", request ->
                    TestWebServer.createResponse(200, answer, "Content-Type", "application/json"));

            sendWithSlowRedis(HttpMethod.POST, "/anthropic/v1/messages",
                    "{\"model\":\"claude-ns\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        }
        assertEventLoopNotBlocked();
    }

    @Test
    void openAiResponsesDoesNotBlockEventLoop() {
        String answer = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-3-turbo\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}";
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, "/openai/v1/responses", request ->
                    TestWebServer.createResponse(200, answer, "Content-Type", "application/json"));

            sendWithSlowRedis(HttpMethod.POST, "/openai/v1/responses",
                    "{\"model\":\"gpt-3-turbo\",\"store\":false,\"input\":\"hi\"}");
        }
        assertEventLoopNotBlocked();
    }

    /**
     * Sends one warm-up request with fast Redis (class loading and JIT on the first request may exceed
     * the event-loop budget), then the measured one with slow Redis writes and the checker armed.
     */
    private void sendWithSlowRedis(HttpMethod method, String path, String body) {
        verify(send(method, path, null, body, "content-type", "application/json"), 200);

        ((VertxInternal) dial.getVertx()).blockedThreadChecker().setThreadBlockedHandler(event -> {
            Thread thread = event.thread();
            StackTraceElement[] frames = thread.getStackTrace();
            // only blocking Redis calls count: slow CI boxes may stall the event loop in logging or JIT linking too
            if (Arrays.stream(frames).noneMatch(frame -> frame.getClassName().startsWith("org.redisson."))) {
                return;
            }
            String stack = Arrays.stream(frames)
                    .map(frame -> "\tat " + frame)
                    .collect(Collectors.joining("\n"));
            eventLoopBlocks.add(thread.getName() + " blocked for " + TimeUnit.NANOSECONDS.toMillis(event.duration()) + " ms\n" + stack);
        });
        SlowRedisHook.enabled = true;
        try {
            verify(send(method, path, null, body, "content-type", "application/json"), 200);
        } finally {
            SlowRedisHook.enabled = false;
        }
    }

    private void assertEventLoopNotBlocked() {
        assertTrue(eventLoopBlocks.isEmpty(), () -> "Event loop was blocked:\n" + eventLoopBlocks.getFirst());
    }

    /**
     * Redisson netty hook delaying every outgoing Redis command by {@link #REDIS_WRITE_DELAY_MS} while enabled.
     * Writes stay FIFO per channel (Redisson matches replies to commands by order), so a write issued after
     * disabling still waits for the delayed ones before it. Instantiated by Redisson from the {@code redis.nettyHook} setting.
     */
    public static class SlowRedisHook implements NettyHook {

        static volatile boolean enabled;

        @Override
        public void afterBoostrapInitialization(Bootstrap bootstrap) {
        }

        @Override
        public void afterChannelInitialization(Channel channel) {
            channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                // accessed only from the channel's event loop
                private final Queue<DelayedWrite> queue = new ArrayDeque<>();

                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    if (!enabled && queue.isEmpty()) {
                        ctx.write(msg, promise);
                        return;
                    }
                    long due = System.nanoTime() + (enabled ? TimeUnit.MILLISECONDS.toNanos(REDIS_WRITE_DELAY_MS) : 0);
                    queue.add(new DelayedWrite(msg, promise, due));
                    if (queue.size() == 1) {
                        drainLater(ctx);
                    }
                }

                private void drainLater(ChannelHandlerContext ctx) {
                    ctx.executor().schedule(() -> {
                        while (!queue.isEmpty() && queue.peek().due() <= System.nanoTime()) {
                            DelayedWrite write = queue.poll();
                            ctx.writeAndFlush(write.msg(), write.promise());
                        }
                        if (!queue.isEmpty()) {
                            drainLater(ctx);
                        }
                    }, Math.max(0, queue.peek().due() - System.nanoTime()), TimeUnit.NANOSECONDS);
                }
            });
        }

        private record DelayedWrite(Object msg, ChannelPromise promise, long due) {
        }
    }
}
