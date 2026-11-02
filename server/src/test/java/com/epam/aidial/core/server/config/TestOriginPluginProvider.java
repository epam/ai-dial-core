package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.OriginPlugin;
import com.epam.aidial.core.plugin.OriginPluginProvider;
import com.epam.aidial.core.plugin.OriginRequest;
import com.epam.aidial.core.plugin.OriginResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.ReadStream;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Origin plugin provider of type {@code test-origin}, registered on the test classpath. It answers like an HTTP
 * origin, by the path: a chat completion (SSE when the body asks for {@code stream}), an embedding list, a response
 * and its item operations, an Anthropic message and its token count. The setting {@code greeting} is the text it
 * answers with. Client headers, which Core forwards to an origin, steer the other cases:
 * <ul>
 *   <li>{@code X-Test-Status: <code>} answers that status;</li>
 *   <li>{@code X-Test-Status-For-Endpoint: <endpoint>=<code>} answers that status to the call for that upstream;</li>
 *   <li>{@code X-Test-Fail: call} fails the call;</li>
 *   <li>{@code X-Test-Hold} streams one event every 100 ms until {@link OriginResponse#cancel()}, as a long answer does.</li>
 * </ul>
 * The setting {@code failCreate: true} makes {@link #create} throw. Tests clear the recorded state with {@link #reset}.
 */
public class TestOriginPluginProvider implements OriginPluginProvider {

    public static final String TYPE = "test-origin";

    /**
     * Every request a {@code test-origin} plugin was called with, oldest first.
     */
    public static final List<OriginRequest> REQUESTS = new CopyOnWriteArrayList<>();
    public static final AtomicInteger CANCELLED = new AtomicInteger();
    /**
     * The value of {@link #CANCELLED} when each request arrived, in the order of {@link #REQUESTS}.
     */
    public static final List<Integer> CANCELLED_BEFORE = new CopyOnWriteArrayList<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static void reset() {
        REQUESTS.clear();
        CANCELLED.set(0);
        CANCELLED_BEFORE.clear();
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public List<Problem> validate(ObjectNode settings) {
        JsonNode greeting = settings.get("greeting");
        return greeting == null || greeting.isTextual() ? List.of() : List.of(new Problem("greeting", "greeting must be a string"));
    }

    @Override
    public OriginPlugin create(ObjectNode settings) {
        if (settings.path("failCreate").asBoolean()) {
            throw new IllegalStateException("test-origin was told not to start");
        }
        String greeting = settings.path("greeting").asText("ok");
        return request -> answer(request, greeting);
    }

    private static Future<OriginResponse> answer(OriginRequest request, String greeting) {
        CANCELLED_BEFORE.add(CANCELLED.get());
        REQUESTS.add(request);
        MultiMap headers = request.headers();
        if ("call".equals(headers.get("X-Test-Fail"))) {
            return Future.failedFuture(new IllegalStateException("test-origin failed the call"));
        }
        int status = status(headers);
        if (status != 200) {
            return Future.succeededFuture(json(status, "{\"error\":{\"message\":\"test-origin answered " + status + "\"}}"));
        }
        String path = request.uri().split("\\?", 2)[0];
        if (path.endsWith("/chat/completions") || path.endsWith("/completions")) {
            return Future.succeededFuture(isStreaming(request.body()) ? chatStream(greeting, headers.contains("X-Test-Hold")) : json(200, chat(greeting)));
        }
        if (path.endsWith("/embeddings")) {
            return Future.succeededFuture(json(200, """
                    {"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1]}],
                     "usage":{"prompt_tokens":1,"total_tokens":1}}"""));
        }
        if (path.equals("/openai/v1/responses")) {
            return Future.succeededFuture(json(200, """
                    {"id":"plugin-resp-1","object":"response","status":"completed",
                     "output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"%s"}]}],
                     "usage":{"input_tokens":10,"output_tokens":5,"total_tokens":15}}""".formatted(greeting)));
        }
        if (path.startsWith("/openai/v1/responses/")) {
            String id = path.substring("/openai/v1/responses/".length()).replace("/cancel", "");
            String state = path.endsWith("/cancel") ? "cancelled" : "completed";
            return Future.succeededFuture(request.method() == HttpMethod.DELETE
                    ? json(200, "{\"id\":\"%s\",\"object\":\"response\",\"deleted\":true}".formatted(id))
                    : json(200, "{\"id\":\"%s\",\"object\":\"response\",\"status\":\"%s\"}".formatted(id, state)));
        }
        if (path.equals("/anthropic/v1/messages/count_tokens")) {
            return Future.succeededFuture(json(200, "{\"input_tokens\":10}"));
        }
        if (path.equals("/anthropic/v1/messages")) {
            return Future.succeededFuture(json(200, """
                    {"id":"msg_1","type":"message","role":"assistant","model":"app","content":[{"type":"text","text":"%s"}],
                     "stop_reason":"end_turn","usage":{"input_tokens":10,"output_tokens":5}}""".formatted(greeting)));
        }
        return Future.succeededFuture(json(404, "{\"error\":{\"message\":\"test-origin serves no " + path + "\"}}"));
    }

    private static int status(MultiMap headers) {
        String forEndpoint = headers.get("X-Test-Status-For-Endpoint");
        if (forEndpoint != null) {
            String[] parts = forEndpoint.split("=", 2);
            return parts[0].equals(headers.get("X-UPSTREAM-ENDPOINT")) ? Integer.parseInt(parts[1]) : 200;
        }
        String status = headers.get("X-Test-Status");
        return status == null ? 200 : Integer.parseInt(status);
    }

    private static boolean isStreaming(Buffer body) {
        if (body.length() == 0) {
            return false;
        }
        try {
            return MAPPER.readTree(body.getBytes()).path("stream").asBoolean();
        } catch (Exception e) {
            return false;
        }
    }

    private static String chat(String greeting) {
        return """
                {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"test",
                 "choices":[{"index":0,"message":{"role":"assistant","content":"%s"},"finish_reason":"stop"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".formatted(greeting);
    }

    private static OriginResponse chatStream(String greeting, boolean hold) {
        Deque<Buffer> events = new ArrayDeque<>();
        events.add(event("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"test\","
                + "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"" + greeting + "\"}}]}"));
        if (!hold) {
            events.add(event("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"test\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"!\"}}]}"));
            events.add(event("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"test\","
                    + "\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],"
                    + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}"));
            events.add(Buffer.buffer("data: [DONE]\n\n"));
        }
        return new Answer(200, MultiMap.caseInsensitiveMultiMap().add("Content-Type", "text/event-stream"),
                new ChunkStream(events, hold));
    }

    private static Buffer event(String data) {
        return Buffer.buffer("data: " + data + "\n\n");
    }

    private static OriginResponse json(int status, String body) {
        Deque<Buffer> chunks = new ArrayDeque<>();
        chunks.add(Buffer.buffer(body));
        return new Answer(status, MultiMap.caseInsensitiveMultiMap().add("Content-Type", "application/json"),
                new ChunkStream(chunks, false));
    }

    private record Answer(int statusCode, MultiMap headers, ChunkStream body) implements OriginResponse {
        @Override
        public void cancel() {
            CANCELLED.incrementAndGet();
            body.cancelled = true;
        }
    }

    /**
     * Emits its chunks on the context it was created on, once a handler is set and while there is demand, then ends.
     * One that holds never ends: it adds an event every 100 ms until it is cancelled.
     */
    private static final class ChunkStream implements ReadStream<Buffer> {

        private final Context context = Vertx.currentContext();
        private final Deque<Buffer> chunks;
        private final boolean hold;
        private Handler<Buffer> handler;
        private Handler<Void> endHandler;
        private long demand = Long.MAX_VALUE;
        private boolean ended;
        private boolean ticking;
        volatile boolean cancelled;

        ChunkStream(Deque<Buffer> chunks, boolean hold) {
            this.chunks = chunks;
            this.hold = hold;
        }

        @Override
        public ReadStream<Buffer> exceptionHandler(Handler<Throwable> handler) {
            return this;
        }

        @Override
        public ReadStream<Buffer> handler(Handler<Buffer> handler) {
            this.handler = handler;
            schedule();
            return this;
        }

        @Override
        public ReadStream<Buffer> pause() {
            demand = 0;
            return this;
        }

        @Override
        public ReadStream<Buffer> resume() {
            return fetch(Long.MAX_VALUE);
        }

        @Override
        public ReadStream<Buffer> fetch(long amount) {
            demand = amount == Long.MAX_VALUE || demand + amount < 0 ? Long.MAX_VALUE : demand + amount;
            schedule();
            return this;
        }

        @Override
        public ReadStream<Buffer> endHandler(Handler<Void> endHandler) {
            this.endHandler = endHandler;
            schedule();
            return this;
        }

        private void schedule() {
            context.runOnContext(ignored -> drain());
        }

        private void drain() {
            while (handler != null && demand > 0 && !chunks.isEmpty()) {
                if (demand != Long.MAX_VALUE) {
                    demand--;
                }
                handler.handle(chunks.poll());
            }
            if (hold && !ticking) {
                ticking = true;
                context.owner().setPeriodic(100, timer -> {
                    if (cancelled) {
                        context.owner().cancelTimer(timer);
                        return;
                    }
                    chunks.add(event("{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"test\","
                            + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\".\"}}]}"));
                    drain();
                });
            }
            if (chunks.isEmpty() && !hold && !ended && handler != null && endHandler != null) {
                ended = true;
                endHandler.handle(null);
            }
        }
    }
}
