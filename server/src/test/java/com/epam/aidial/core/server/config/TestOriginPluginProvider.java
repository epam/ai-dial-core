package com.epam.aidial.core.server.config;

import com.epam.aidial.core.plugin.OriginCall;
import com.epam.aidial.core.plugin.OriginHost;
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
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.streams.ReadStream;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Origin plugin provider of type {@code test-origin}, registered on the test classpath. It answers like an HTTP
 * origin, by the path: a chat completion (SSE when the body asks for {@code stream}), an embedding list, a response
 * and its item operations, an Anthropic message and its token count. The setting {@code greeting} is the text it
 * answers with. Client headers, which Core forwards to an origin, steer the other cases:
 * <ul>
 *   <li>{@code X-Test-Status: <code>} answers that status;</li>
 *   <li>{@code X-Test-Status-For-Endpoint: <endpoint>=<code>} answers that status to the call for that upstream;</li>
 *   <li>{@code X-Test-Fail: call} fails the call, and {@code X-Test-Fail: null} completes it with no answer;</li>
 *   <li>{@code X-Test-Hold} streams one event every 100 ms until {@link OriginResponse#cancel()}, as a long answer does;</li>
 *   <li>{@code X-Test-Call: <path>} calls that path through the host with the request's {@code bodyTree} and relays the
 *   call's answer, a cancel of it counted in {@link #CANCELLED} and passed on. The call carries every header but the
 *   {@code X-Test-Call*} ones, so a plugin of this type serving the target does not call again;</li>
 *   <li>{@code X-Test-Call-Again} makes the call carry the {@code X-Test-Call*} headers too, so such a target calls again;</li>
 *   <li>{@code X-Test-Call-Model: <name>} sets {@code model} in the tree first;</li>
 *   <li>{@code X-Test-Call-Cancel} cancels the call after its first chunk and answers a chat completion itself;</li>
 *   <li>{@code X-Test-Await-Cancel} answers nothing until the host says the request is cancelled, then counts it in
 *   {@link #TOLD_CANCELLED} and fails the call.</li>
 * </ul>
 * On {@code POST /decide}, the request of a router in decision mode, it speaks the decide protocol and records the
 * request in {@link #DECIDED}, steered by these client headers:
 * <ul>
 *   <li>{@code X-Test-Decide: <name>[,<name>]} answers {@code {"candidates": [...]}}, and {@code X-Test-Redecide} the
 *   same once {@code X-DIAL-ROUTER-ATTEMPTS} is present;</li>
 *   <li>{@code X-Test-Decide-Status: <code>} with {@code X-Test-Decide-Body: <json>} answers that status and body;</li>
 *   <li>{@code X-Test-Decide-Stall-Body} answers {@code 200} and never ends the body;</li>
 *   <li>{@code X-Test-Decide-Delay-Ms: <ms>} answers after that delay;</li>
 *   <li>{@code X-Test-Decide-Strip} removes {@code messages} from its body tree first;</li>
 *   <li>{@code X-Test-Decide-Call: <path>} calls that path through the host and answers once the call's answer ended;
 *   {@code X-Test-Decide-Call-Stream: <path>} starts a streamed call and answers without waiting;</li>
 *   <li>on a notice, {@code X-Test-Notice-Call: <path>} calls that path through the host first.</li>
 * </ul>
 * {@code X-Test-Fail: call} and {@code X-Test-Await-Cancel} apply to a decide request too.
 * The setting {@code failCreate: true} makes {@link #create} throw, and {@code closeFails: true} makes the plugin's
 * {@link OriginPlugin#close} throw once it is recorded in {@link #CLOSED}. Tests clear the recorded state with
 * {@link #reset}.
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
    /**
     * The host and the Vert.x context of each request, in the order of {@link #REQUESTS}, for a call a test makes later.
     */
    public static final List<Host> HOSTS = new CopyOnWriteArrayList<>();
    /**
     * The {@code Vertx} the last plugin was created with.
     */
    public static final AtomicReference<Vertx> CREATED_WITH = new AtomicReference<>();
    /**
     * How many requests the host told it were cancelled before it answered.
     */
    public static final AtomicInteger TOLD_CANCELLED = new AtomicInteger();
    /**
     * Every plugin closed, oldest first.
     */
    public static final List<Closed> CLOSED = new CopyOnWriteArrayList<>();
    /**
     * Every decide or notice request a {@code test-origin} plugin was called with, oldest first. Their hosts are in
     * {@link #HOSTS} too.
     */
    public static final List<Decided> DECIDED = new CopyOnWriteArrayList<>();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CALL_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"ask\"}]}";

    public static void reset() {
        REQUESTS.clear();
        CANCELLED.set(0);
        CANCELLED_BEFORE.clear();
        HOSTS.clear();
        TOLD_CANCELLED.set(0);
        CLOSED.clear();
        DECIDED.clear();
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
    public OriginPlugin create(ObjectNode settings, Vertx vertx) {
        CREATED_WITH.set(vertx);
        if (settings.path("failCreate").asBoolean()) {
            throw new IllegalStateException("test-origin was told not to start");
        }
        return new TestPlugin(settings.path("greeting").asText("ok"), settings.path("closeFails").asBoolean());
    }

    private record TestPlugin(String greeting, boolean closeFails) implements OriginPlugin {

        @Override
        public Future<OriginResponse> handle(OriginRequest request, OriginHost host) {
            return answer(request, host, greeting);
        }

        @Override
        public void close() {
            CLOSED.add(new Closed(greeting, Context.isOnEventLoopThread()));
            if (closeFails) {
                throw new IllegalStateException("test-origin was told not to close");
            }
        }
    }

    private static Future<OriginResponse> answer(OriginRequest request, OriginHost host, String greeting) {
        if ("/decide".equals(request.uri())) {
            return decide(request, host);
        }
        CANCELLED_BEFORE.add(CANCELLED.get());
        REQUESTS.add(request);
        HOSTS.add(new Host(host, Vertx.currentContext()));
        MultiMap headers = request.headers();
        String callPath = headers.get("X-Test-Call");
        if (callPath != null) {
            return call(request, host, callPath, greeting);
        }
        if (headers.contains("X-Test-Await-Cancel")) {
            return host.cancelled().compose(ignored -> {
                TOLD_CANCELLED.incrementAndGet();
                return Future.failedFuture(new IllegalStateException("test-origin stopped: its client went away"));
            });
        }
        if ("call".equals(headers.get("X-Test-Fail"))) {
            return Future.failedFuture(new IllegalStateException("test-origin failed the call"));
        }
        if ("null".equals(headers.get("X-Test-Fail"))) {
            return Future.succeededFuture(null);
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

    private static Future<OriginResponse> call(OriginRequest request, OriginHost host, String path, String greeting) {
        ObjectNode body = request.bodyTree();
        String model = request.headers().get("X-Test-Call-Model");
        if (model != null) {
            body.put("model", model);
        }
        MultiMap headers = MultiMap.caseInsensitiveMultiMap();
        request.headers().forEach(header -> {
            if (request.headers().contains("X-Test-Call-Again")
                    || !header.getKey().regionMatches(true, 0, "X-Test-Call", 0, "X-Test-Call".length())) {
                headers.add(header.getKey(), header.getValue());
            }
        });
        Future<OriginResponse> answer = host.call(new OriginCall(path, headers, body));
        if (!request.headers().contains("X-Test-Call-Cancel")) {
            return answer.map(Relay::new);
        }
        return answer.compose(called -> {
            Promise<OriginResponse> own = Promise.promise();
            called.body().handler(chunk -> {
                if (!own.future().isComplete()) {
                    called.cancel();
                    own.complete(json(200, chat(greeting)));
                }
            });
            return own.future();
        });
    }

    private static Future<OriginResponse> decide(OriginRequest request, OriginHost host) {
        Decided decided = new Decided(request, new AtomicBoolean(), Promise.promise(), new CopyOnWriteArrayList<>());
        DECIDED.add(decided);
        HOSTS.add(new Host(host, Vertx.currentContext()));
        host.cancelled().onComplete(ignored -> decided.cancelled().set(true));
        Future<OriginResponse> answer;
        try {
            answer = verdict(request, host, decided);
        } catch (RuntimeException e) {
            answer = Future.failedFuture(e);
        }
        return answer.onComplete(ignored -> decided.settled().tryComplete());
    }

    private static Future<OriginResponse> verdict(OriginRequest request, OriginHost host, Decided decided) {
        MultiMap headers = request.headers();
        Context context = Vertx.currentContext();
        if (headers.contains("X-DIAL-ROUTER-NOTICE")) {
            String path = headers.get("X-Test-Notice-Call");
            return (path == null ? Future.<Void>succeededFuture() : callThrough(host, path, false, decided)).map(ignored -> json(200, "{}"));
        }
        if ("call".equals(headers.get("X-Test-Fail"))) {
            return Future.failedFuture(new IllegalStateException("test-origin failed the decision"));
        }
        if (headers.contains("X-Test-Await-Cancel")) {
            return host.cancelled().compose(ignored -> {
                TOLD_CANCELLED.incrementAndGet();
                return Future.failedFuture(new IllegalStateException("test-origin stopped: the decision was cancelled"));
            });
        }
        if (headers.contains("X-Test-Decide-Stall-Body")) {
            return Future.succeededFuture(new Answer(200, MultiMap.caseInsensitiveMultiMap().add("Content-Type", "application/json"),
                    new ChunkStream(new ArrayDeque<>(), false, true)));
        }
        if (headers.contains("X-Test-Decide-Strip") && request.bodyTree() != null) {
            request.bodyTree().remove("messages");
        }
        String streamed = headers.get("X-Test-Decide-Call-Stream");
        if (streamed != null) {
            callThrough(host, streamed, true, decided);
        }
        String path = headers.get("X-Test-Decide-Call");
        Future<Void> before = path == null ? Future.succeededFuture() : callThrough(host, path, false, decided);
        String delay = headers.get("X-Test-Decide-Delay-Ms");
        if (delay != null) {
            before = before.compose(ignored -> {
                Promise<Void> waited = Promise.promise();
                context.owner().setTimer(Long.parseLong(delay), timer -> waited.complete());
                return waited.future();
            });
        }
        String status = headers.get("X-Test-Decide-Status");
        return before.map(ignored -> status != null
                ? json(Integer.parseInt(status), headers.get("X-Test-Decide-Body"))
                : json(200, "{\"candidates\":" + candidates(headers) + "}"));
    }

    private static String candidates(MultiMap headers) {
        String names = headers.contains("X-DIAL-ROUTER-ATTEMPTS") && headers.contains("X-Test-Redecide")
                ? headers.get("X-Test-Redecide") : headers.get("X-Test-Decide");
        List<String> quoted = names == null ? List.of() : Arrays.stream(names.split(",")).map(name -> "\"" + name.trim() + "\"").toList();
        return "[" + String.join(",", quoted) + "]";
    }

    /**
     * Calls {@code path} through the host with a small chat body of its own, and records how the call's answer ended.
     * The future completes when that answer has ended or failed.
     */
    private static Future<Void> callThrough(OriginHost host, String path, boolean stream, Decided decided) {
        Call call = new Call(path, new AtomicReference<>("sent"));
        decided.calls().add(call);
        ObjectNode body;
        try {
            body = (ObjectNode) MAPPER.readTree(CALL_BODY);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        if (stream) {
            body.put("stream", true);
        }
        Promise<Void> done = Promise.promise();
        host.call(new OriginCall(path, MultiMap.caseInsensitiveMultiMap(), body)).onComplete(result -> {
            if (result.failed()) {
                call.outcome().set("failed: " + result.cause().getMessage());
                done.complete();
                return;
            }
            OriginResponse answer = result.result();
            call.outcome().set(Integer.toString(answer.statusCode()));
            answer.body().exceptionHandler(error -> {
                call.outcome().set(answer.statusCode() + " failed");
                done.tryComplete();
            }).endHandler(ignored -> {
                call.outcome().set(answer.statusCode() + " ended");
                done.tryComplete();
            }).handler(chunk -> {
            });
        });
        return done.future();
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

    public record Host(OriginHost host, Context context) {
    }

    /**
     * A decide or notice request: whether the host reported it cancelled, when the plugin's answer settled, and the
     * calls the plugin made through its host while answering it.
     */
    public record Decided(OriginRequest request, AtomicBoolean cancelled, Promise<Void> settled, List<Call> calls) {
    }

    /**
     * A call a deciding plugin made: {@code sent}, then the answer's status, then {@code <status> ended} or
     * {@code <status> failed} as its body ended, or {@code failed: <message>} when the call itself failed.
     */
    public record Call(String path, AtomicReference<String> outcome) {
    }

    /**
     * A closed plugin: its {@code greeting}, and whether it was closed on an event loop.
     */
    public record Closed(String greeting, boolean onEventLoop) {
    }

    /**
     * A call's answer relayed as the plugin's own.
     */
    private record Relay(OriginResponse called) implements OriginResponse {

        @Override
        public int statusCode() {
            return called.statusCode();
        }

        @Override
        public MultiMap headers() {
            return called.headers();
        }

        @Override
        public ReadStream<Buffer> body() {
            return called.body();
        }

        @Override
        public void cancel() {
            CANCELLED.incrementAndGet();
            called.cancel();
        }
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
        // emits its chunks and then neither adds nor ends: a body that stalls
        private final boolean stall;
        private Handler<Buffer> handler;
        private Handler<Void> endHandler;
        private long demand = Long.MAX_VALUE;
        private boolean ended;
        private boolean ticking;
        volatile boolean cancelled;

        ChunkStream(Deque<Buffer> chunks, boolean hold) {
            this(chunks, hold, false);
        }

        ChunkStream(Deque<Buffer> chunks, boolean hold, boolean stall) {
            this.chunks = chunks;
            this.hold = hold;
            this.stall = stall;
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
            if (chunks.isEmpty() && !hold && !stall && !ended && handler != null && endHandler != null) {
                ended = true;
                endHandler.handle(null);
            }
        }
    }
}
