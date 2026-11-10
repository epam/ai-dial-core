package com.epam.aidial.core.server;

import com.epam.aidial.core.plugin.OriginCall;
import com.epam.aidial.core.plugin.OriginCaller;
import com.epam.aidial.core.plugin.OriginRequest;
import com.epam.aidial.core.plugin.OriginResponse;
import com.epam.aidial.core.server.config.TestOriginPluginProvider;
import com.epam.aidial.core.server.config.TestOriginPluginProvider.Decided;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.MultiMap;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A router in decision mode whose interface is served by an origin plugin: the plugin answers the decide request in
 * process, and Core serves the deployment it names.
 */
class EmbeddedDecisionApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/router-decision-embedded.json";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String STREAM_BODY = "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String COMPLETION = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"upstream\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}}";
    private static final String RESPONSE = "{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"upstream\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String EMBEDDINGS = "{\"object\":\"list\",\"data\":[],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
    private static final String CHUNK = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
            + "\"delta\":{\"content\":\"x\"}}]}\n\n";

    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();

    @BeforeEach
    void resetTestOrigin() {
        TestOriginPluginProvider.reset();
    }

    // router-deployment "Decision mode"

    @Test
    @DialConfigLocation(CONFIG)
    void testEmbeddedInterfaceNextToHttpOne() {
        try (TestWebServer server = server(request -> isDecide(request)
                ? json(200, "{\"candidates\": [\"m1\"]}") : answer(request))) {
            verify(chat("r4", CHAT_BODY, "X-Test-Decide", "m1"), 200);
            assertTrue(received.stream().noneMatch(this::isDecide), "the chat completion is decided by the plugin");

            verify(send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"r4\",\"input\":\"hi\"}",
                    "content-type", "application/json"), 200);
        }
        assertEquals(1, TestOriginPluginProvider.DECIDED.size());
        assertEquals(1, received.stream().filter(this::isDecide).count(), "the Responses request is decided over HTTP");
    }

    // router-deployment "Decision router settings"

    @Test
    @DialConfigLocation(CONFIG)
    void testEmbeddedDecisionRouterListed() {
        verify(send(HttpMethod.GET, "/v1/routers/r1"), 200);
    }

    // router-deployment "Router identity through user info"

    @Test
    @DialConfigLocation(CONFIG)
    void testEmbeddedRouterGetsTheCaller() {
        try (TestWebServer server = server(this::answer)) {
            verify(chat("r1", CHAT_BODY, "X-Test-Decide", "m1"), 200);
        }
        OriginCaller caller = decided(0).request().caller();
        assertEquals("EPM-RTC-GPT", caller.project());
        assertEquals(List.of("default"), caller.roles());
        assertNull(caller.userId());
    }

    // router-deployment "Decision by an embedded plugin"

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testPluginDecides() {
        try (TestWebServer server = server(this::answer)) {
            Response response = chat("r1", CHAT_BODY, "X-Test-Decide", "m1");

            verify(response, 200);
            assertEquals("r1", ProxyUtil.MAPPER.readTree(response.body()).path("model").asText(), response.body());
        }
        OriginRequest decide = decided(0).request();
        assertEquals("/decide", decide.uri());
        assertEquals("r1", decide.headers().get("X-DIAL-ROUTER"));
        assertEquals("openaiChatCompletions", decide.headers().get("X-DIAL-API"));
        assertNotNull(decide.headers().get("api-key"));
        assertNotEquals("proxyKey1", decide.headers().get("api-key"));
        assertEquals(0, decide.body().length());
        assertNull(decide.bodyTree());
        assertEquals(1, received.stream().filter(request -> isFor(request, "m1")).count());

        List<AnalyticsLogContext> rows = awaitAnalyticsRows(2);
        assertEquals(1, rows.stream().filter(row -> "m1".equals(row.getDeploymentName()) && row.getDecision() == null).count());
        assertEquals(1, rows.stream().filter(row -> row.getDecision() != null && "r1".equals(row.getDeploymentName())).count());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPluginRedecides() {
        try (TestWebServer server = server(request -> isFor(request, "m1")
                ? json(503, "{\"error\":{\"message\":\"busy\"}}") : answer(request))) {
            verify(chat("r1", CHAT_BODY, "X-Test-Decide", "m1", "X-Test-Redecide", "m2"), 200);
        }
        assertEquals(2, TestOriginPluginProvider.DECIDED.size());
        assertNull(decided(0).request().headers().get("X-DIAL-ROUTER-ATTEMPTS"));
        String attempts = decided(1).request().headers().get("X-DIAL-ROUTER-ATTEMPTS");
        assertTrue(attempts.contains("\"deployment\":\"m1\""), attempts);
        assertEquals(1, received.stream().filter(request -> isFor(request, "m2")).count());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNoticeToThePlugin() {
        try (TestWebServer server = server(request -> brokenStream())) {
            streamIgnoringBreak("r1", "X-Test-Decide", "m1");
            Decided notice = awaitDecided(1);

            assertEquals("/decide", notice.request().uri());
            assertEquals("true", notice.request().headers().get("X-DIAL-ROUTER-NOTICE"));
            assertNull(notice.request().headers().get("api-key"));
            assertEquals(0, notice.request().body().length());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testClientGoneWhilePluginDecides() {
        try (TestWebServer server = server(this::answer)) {
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/openai/deployments/r1/chat/completions"))
                    .header("api-key", "proxyKey1").header("content-type", "application/json")
                    .header("X-Test-Decide", "m1").header("X-Test-Decide-Delay-Ms", "500")
                    .timeout(Duration.ofMillis(100))
                    .POST(HttpRequest.BodyPublishers.ofString(CHAT_BODY)).build();
            try {
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(request, HttpResponse.BodyHandlers.ofString());
            } catch (HttpTimeoutException expected) {
                // the client gave up while the plugin decides
            }
            Decided decide = awaitDecided(0);
            decide.settled().future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertFalse(decide.cancelled().get(), "Core does not watch the client of a decide request");
            // the pass goes on to the chosen model, as for an HTTP router: let it finish before Core shuts down
            awaitCondition(() -> received.stream().anyMatch(served -> isFor(served, "m1")));
            Thread.sleep(500);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testEmbeddingsDecidedByThePlugin() {
        try (TestWebServer server = server(this::answer)) {
            verify(send(HttpMethod.POST, "/openai/deployments/re/embeddings", null, "{\"input\":\"hi\"}",
                    "content-type", "application/json", "X-Test-Decide", "e1"), 200);
        }
        assertEquals("openaiEmbeddings", decided(0).request().headers().get("X-DIAL-API"));
        assertEquals(1, received.stream().filter(request -> isFor(request, "e1")).count());
    }

    // router-deployment "Embedded decision failures"

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testPluginFails() {
        try (TestWebServer server = server(this::answer)) {
            verify(chat("r1", CHAT_BODY, "X-Test-Decide", "m1", "X-Test-Fail", "call"), 502);
        }
        assertTrue(received.isEmpty(), "no candidate receives a request");
        Decided decide = decided(0);
        decide.settled().future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(300);
        assertFalse(decide.cancelled().get(), "a plugin whose answer failed is not told it was cancelled");
        assertTrue(awaitAnalyticsRows(0).isEmpty(), "no analytics row is written");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPluginWithoutAnEntry() {
        verify(chat("r9", CHAT_BODY, "X-Test-Decide", "m1"), 502);
        assertTrue(TestOriginPluginProvider.DECIDED.isEmpty());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPluginTooSlow() {
        long start = System.currentTimeMillis();
        verify(chat("rt", CHAT_BODY, "X-Test-Await-Cancel", "true"), 504);
        assertTrue(System.currentTimeMillis() - start < 1000);
        awaitCondition(() -> TestOriginPluginProvider.TOLD_CANCELLED.get() == 1);
        assertTrue(decided(0).cancelled().get());

        verify(chat("rt", CHAT_BODY, "X-Test-Decide-Stall-Body", "true"), 504);
        awaitCondition(() -> decided(1).cancelled().get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testPluginRefusesTheRequest() {
        String refusal = "{\"error\": {\"message\": \"state either tier or target\"}}";
        try (TestWebServer server = server(this::answer)) {
            verify(chat("r1", CHAT_BODY, "X-Test-Decide-Status", "400", "X-Test-Decide-Body", refusal), 400, refusal);
        }
        assertTrue(received.isEmpty(), "no candidate receives a request");
        Decided decide = decided(0);
        decide.settled().future().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        Thread.sleep(300);
        assertFalse(decide.cancelled().get(), "a plugin that answered is not told it was cancelled");
    }

    // router-deployment "Calls of an embedded router while it decides"

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testPluginAsksModelWhileDeciding() {
        try (TestWebServer server = server(this::answer)) {
            Response response = chat("r1", CHAT_BODY, "X-Test-Decide", "m1",
                    "X-Test-Decide-Call", "/openai/deployments/m2/chat/completions");

            verify(response, 200);
            JsonNode usage = ProxyUtil.MAPPER.readTree(response.body()).path("statistics").path("usage_per_model");
            List<String> models = new ArrayList<>();
            usage.forEach(entry -> models.add(entry.path("model").asText()));
            assertTrue(models.contains("m2"), response.body());
        }
        assertEquals("200 ended", decided(0).calls().get(0).outcome().get());
        List<AnalyticsLogContext> rows = awaitAnalyticsRows(3);
        AnalyticsLogContext asked = rows.stream().filter(row -> "m2".equals(row.getDeploymentName())).findFirst().orElseThrow();
        assertEquals("r1", asked.getParentDeployment());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testCallAfterTheDecision() {
        // the chosen model answers slowly, so the pass is still serving it when the plugin calls: only the end of the
        // decide call, not the end of the pass, refuses the call
        try (TestWebServer server = server(request -> isFor(request, "m1")
                ? json(200, COMPLETION).setHeadersDelay(2, TimeUnit.SECONDS) : answer(request))) {
            CompletableFuture<Response> pending = CompletableFuture.supplyAsync(() -> chat("r1", CHAT_BODY, "X-Test-Decide", "m1"));
            awaitCondition(() -> received.stream().anyMatch(request -> isFor(request, "m1")));

            TestOriginPluginProvider.Host kept = TestOriginPluginProvider.HOSTS.get(0);
            CompletableFuture<OriginResponse> answer = new CompletableFuture<>();
            kept.context().runOnContext(ignored -> kept.host()
                    .call(new OriginCall("/openai/deployments/m2/chat/completions", MultiMap.caseInsensitiveMultiMap(),
                            ProxyUtil.MAPPER.createObjectNode().put("model", "m2")))
                    .onComplete(result -> {
                        if (result.succeeded()) {
                            answer.complete(result.result());
                        } else {
                            answer.completeExceptionally(result.cause());
                        }
                    }));

            assertEquals(401, answer.get(5, TimeUnit.SECONDS).statusCode());
            assertFalse(pending.isDone(), "the pass was still serving the chosen model");
            verify(pending.get(10, TimeUnit.SECONDS), 200);
        }
        assertEquals(0, received.stream().filter(request -> isFor(request, "m2")).count());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testCallOpenWhenTheDecisionEnds() {
        try (TestWebServer server = server(request -> isFor(request, "m2") ? slowStream()
                : isFor(request, "m1") ? json(200, COMPLETION).setHeadersDelay(2, TimeUnit.SECONDS)
                : answer(request))) {
            CompletableFuture<Response> pending = CompletableFuture.supplyAsync(() -> chat("r1", CHAT_BODY, "X-Test-Decide", "m1",
                    "X-Test-Decide-Call-Stream", "/openai/deployments/m2/chat/completions"));

            Decided decide = awaitDecided(0);
            awaitCondition(() -> !decide.calls().isEmpty());
            TestOriginPluginProvider.Call call = decide.calls().get(0);
            awaitCondition(() -> call.outcome().get().endsWith("failed") || call.outcome().get().startsWith("failed"));
            assertFalse(pending.isDone(), "the call was cut off by the end of the decide call, not by the end of the pass");
            verify(pending.get(10, TimeUnit.SECONDS), 200);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testCallWhileAnsweringNotice() {
        try (TestWebServer server = server(request -> isFor(request, "m1")
                ? brokenStream() : answer(request))) {
            streamIgnoringBreak("r1", "X-Test-Decide", "m1", "X-Test-Notice-Call", "/openai/deployments/m2/chat/completions");
            Decided notice = awaitDecided(1);
            awaitCondition(() -> !notice.calls().isEmpty() && "401 ended".equals(notice.calls().get(0).outcome().get()));
        }
        assertEquals(0, received.stream().filter(request -> isFor(request, "m2")).count());
    }

    // router-deployment "Body of an embedded decide request"

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testBodyAndTree() {
        String chat = "{\"model\":\"rb\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String messages = "{\"model\":\"rb\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
        String responses = "{\"model\":\"rb\",\"input\":\"hi\"}";
        try (TestWebServer server = server(request -> request.getPath().startsWith("/anthropic/") ? json(200, MESSAGE) : answer(request))) {
            verify(chat("rb", CHAT_BODY, "X-Test-Decide", "m1"), 200);
            verify(post("/openai/v1/chat/completions", chat, "X-Test-Decide", "m1"), 200);
            verify(post("/anthropic/v1/messages", messages, "X-Test-Decide", "m1"), 200);
            verify(post("/openai/v1/responses", responses, "X-Test-Decide", "m1"), 200);
        }
        List<String> sent = List.of(CHAT_BODY, chat, messages, responses);
        for (int i = 0; i < sent.size(); i++) {
            OriginRequest decide = decided(i).request();
            assertEquals(sent.get(i), decide.body().toString(), "the client's bytes, unchanged");
            assertNotNull(decide.bodyTree(), sent.get(i));
            assertEquals(ProxyUtil.MAPPER.readTree(sent.get(i)), decide.bodyTree(), "the parsed body");
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPluginChangesItsTree() {
        try (TestWebServer server = server(this::answer)) {
            verify(post("/openai/v1/chat/completions", "{\"model\":\"rb\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "X-Test-Decide", "m1", "X-Test-Decide-Strip", "true"), 200);
        }
        assertFalse(decided(0).request().bodyTree().has("messages"), "the plugin removed them from its own copy");
        RecordedRequest served = single(request -> isFor(request, "m1"));
        assertTrue(served.getBody().readUtf8().contains("\"content\":\"hi\""), "the chosen model gets the client's messages");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNoBodyWithoutTheSetting() {
        try (TestWebServer server = server(this::answer)) {
            verify(post("/openai/v1/chat/completions", "{\"model\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "X-Test-Decide", "m1"), 200);
        }
        assertEquals(0, decided(0).request().body().length());
        assertNull(decided(0).request().bodyTree());
    }

    // router-deployment "Decide request of an in-process call"

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testPluginCallsDecisionRouterThatWantsTheBody() {
        try (TestWebServer server = server(request -> isDecide(request) ? json(200, "{\"candidates\": [\"m1\"]}") : answer(request))) {
            verify(chat("app", CHAT_BODY, "X-Test-Call", "/openai/v1/chat/completions", "X-Test-Call-Model", "r2"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        JsonNode sent = ProxyUtil.MAPPER.readTree(decide.getBody().readUtf8());
        assertEquals("r2", sent.path("model").asText());
        assertEquals("hi", sent.path("messages").path(0).path("content").asText());
        single(request -> isFor(request, "m1"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testFallbackInsideAnInProcessCall() {
        try (TestWebServer server = server(request -> isDecide(request) ? json(200, "{\"candidates\": [\"m1\", \"m2\"]}")
                : isFor(request, "m1") ? json(503, "{\"error\":{\"message\":\"busy\"}}")
                : answer(request))) {
            verify(chat("app", CHAT_BODY, "X-Test-Call", "/openai/v1/chat/completions", "X-Test-Call-Model", "rf"), 200);
        }
        assertTrue(single(request -> isFor(request, "m1")).getBody().readUtf8().contains("\"temperature\""),
                "the first candidate applied its defaults");
        JsonNode served = ProxyUtil.MAPPER.readTree(single(request -> isFor(request, "m2")).getBody().readUtf8());
        assertEquals("hi", served.path("messages").path(0).path("content").asText());
        assertEquals("m2", served.path("model").asText());
        assertFalse(served.has("temperature"), "the fallback starts from the call's tree, not from the failed candidate's");
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testInProcessCallOnDeploymentsPost() {
        try (TestWebServer server = server(request -> isDecide(request) ? json(200, "{\"candidates\": [\"m1\"]}") : answer(request))) {
            verify(chat("app", CHAT_BODY, "X-Test-Call", "/openai/deployments/r2/chat/completions"), 200);
        }
        assertTrue(single(this::isDecide).getBody().readUtf8().contains("\"content\":\"hi\""), "the decide body is the call's tree");
        JsonNode served = ProxyUtil.MAPPER.readTree(single(request -> isFor(request, "m1")).getBody().readUtf8());
        assertEquals("hi", served.path("messages").path(0).path("content").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testInProcessCallToAnEmbeddedRouter() {
        try (TestWebServer server = server(this::answer)) {
            verify(chat("app", CHAT_BODY, "X-Test-Call", "/openai/v1/chat/completions", "X-Test-Call-Model", "rb",
                    "X-Test-Decide", "m1"), 200);
        }
        OriginRequest decide = decided(0).request();
        JsonNode bytes = ProxyUtil.MAPPER.readTree(decide.body().getBytes());
        assertEquals("rb", bytes.path("model").asText());
        assertEquals(bytes, decide.bodyTree(), "the plugin's tree is the call's, as its bytes are");
        single(request -> isFor(request, "m1"));
    }

    private Response post(String path, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "content-type";
        all[1] = "application/json";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return send(HttpMethod.POST, path, null, body, all);
    }

    private RecordedRequest single(Predicate<RecordedRequest> filter) {
        List<RecordedRequest> found = received.stream().filter(filter).toList();
        assertEquals(1, found.size(), () -> received.stream().map(RecordedRequest::getPath).toList().toString());
        return found.get(0);
    }

    private Response chat(String router, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "content-type";
        all[1] = "application/json";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return send(HttpMethod.POST, "/openai/deployments/" + router + "/chat/completions", null, body, all);
    }

    /**
     * A streamed chat completion whose answer may break: the client's view of it does not matter here.
     */
    private void streamIgnoringBreak(String router, String... headers) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/openai/deployments/" + router
                        + "/chat/completions"))
                .header("api-key", "proxyKey1").header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(STREAM_BODY));
        for (int i = 0; i < headers.length; i += 2) {
            builder.header(headers[i], headers[i + 1]);
        }
        try {
            HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception expected) {
            // the client sees its connection broken after the first chunk
        }
    }

    private TestWebServer server(TestWebServer.Handler handler) {
        return new TestWebServer(4848, request -> {
            received.add(request);
            return handler.map(request);
        });
    }

    private MockResponse answer(RecordedRequest request) {
        String path = request.getPath();
        if (path.endsWith("/embeddings")) {
            return json(200, EMBEDDINGS);
        }
        if (path.startsWith("/openai/v1/responses")) {
            return json(200, RESPONSE);
        }
        return json(200, COMPLETION);
    }

    /**
     * The first event of a stream, then the connection drops: an answer broken after bytes reached the client.
     */
    private static MockResponse brokenStream() {
        StringBuilder stream = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            stream.append(CHUNK);
        }
        return new MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
                .setChunkedBody(stream.toString(), 64).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
    }

    /**
     * A stream that takes seconds to arrive.
     */
    private static MockResponse slowStream() {
        StringBuilder stream = new StringBuilder();
        for (int i = 0; i < 20; i++) {
            stream.append(CHUNK);
        }
        stream.append("data: [DONE]\n\n");
        return new MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
                .setChunkedBody(stream.toString(), 32).throttleBody(32, 200, TimeUnit.MILLISECONDS);
    }

    private static MockResponse json(int status, String body) {
        return TestWebServer.createResponse(status, body, "content-type", "application/json");
    }

    /**
     * Whether the request is the one Core sends a deployment's origin: the deployment id header names it on every API.
     */
    private static boolean isFor(RecordedRequest request, String deployment) {
        return deployment.equals(request.getHeader("X-DIAL-DEPLOYMENT-ID"));
    }

    private boolean isDecide(RecordedRequest request) {
        return request.getPath().endsWith("/decide");
    }

    private static Decided decided(int index) {
        return TestOriginPluginProvider.DECIDED.get(index);
    }

    private static Decided awaitDecided(int index) {
        awaitCondition(() -> TestOriginPluginProvider.DECIDED.size() > index);
        return decided(index);
    }
}
