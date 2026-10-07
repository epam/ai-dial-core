package com.epam.aidial.core.server;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.client.NettyHook;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
    private static final String MESSAGES_BODY = "{\"model\":\"claude-ns\",\"max_tokens\":100,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String MESSAGES_ANSWER = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-ns\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String RESPONSES_BODY = "{\"model\":\"gpt-3-turbo\",\"store\":false,\"input\":\"hi\"}";
    private static final String RESPONSES_ANSWER = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"gpt-3-turbo\","
            + "\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}";

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

    static Stream<Arguments> endpoints() {
        return Stream.of(
                // control: already offloads the body handler to the task executor
                Arguments.of("/openai/deployments/gpt-3-turbo/chat/completions", CHAT_BODY, "/chat/completions", CHAT_ANSWER),
                Arguments.of("/openai/v1/chat/completions", CHAT_BODY, "/chat/completions", CHAT_ANSWER),
                Arguments.of("/anthropic/v1/messages", MESSAGES_BODY, "/anthropic/v1/messages", MESSAGES_ANSWER),
                Arguments.of("/openai/v1/responses", RESPONSES_BODY, "/openai/v1/responses", RESPONSES_ANSWER));
    }

    /**
     * Sends one warm-up request with fast Redis (class loading and JIT on the first request may exceed
     * the event-loop budget), then the measured one with slow Redis writes and the checker armed.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("endpoints")
    void requestDoesNotBlockEventLoopOnRedis(String path, String body, String upstreamPath, String upstreamAnswer) {
        try (TestWebServer server = new TestWebServer(4848)) {
            server.map(HttpMethod.POST, upstreamPath, request ->
                    TestWebServer.createResponse(200, upstreamAnswer, "Content-Type", "application/json"));

            verify(send(HttpMethod.POST, path, null, body, "content-type", "application/json"), 200);

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
                verify(send(HttpMethod.POST, path, null, body, "content-type", "application/json"), 200);
            } finally {
                SlowRedisHook.enabled = false;
            }
        }
        assertTrue(eventLoopBlocks.isEmpty(), () -> "Event loop was blocked:\n" + eventLoopBlocks.getFirst());
    }

    /**
     * Redisson netty hook delaying every outgoing Redis command by {@link #REDIS_WRITE_DELAY_MS} while enabled.
     * Redisson runs its own netty threads, so sleeping here is invisible to the Vert.x blocked-thread checker.
     * Instantiated by Redisson from the {@code redis.nettyHook} setting.
     */
    public static class SlowRedisHook implements NettyHook {

        static volatile boolean enabled;

        @Override
        public void afterBoostrapInitialization(Bootstrap bootstrap) {
        }

        @Override
        public void afterChannelInitialization(Channel channel) {
            channel.pipeline().addFirst(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
                    if (enabled) {
                        // ponytail: concurrent writes on one netty thread are delayed serially; ~10 of them would hit
                        // Redisson's 3 s timeout, schedule the write instead if a test ever issues that many
                        Thread.sleep(REDIS_WRITE_DELAY_MS);
                    }
                    ctx.write(msg, promise);
                }
            });
        }
    }
}
