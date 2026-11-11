package com.epam.aidial.core.server;

import com.epam.aidial.core.plugin.Call;
import com.epam.aidial.core.plugin.Caller;
import com.epam.aidial.core.plugin.Request;
import com.epam.aidial.core.server.config.TestOriginPluginProvider;
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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An origin plugin calling deployments in process, and what it learns of its caller. Each test creates its own
 * {@code plugins} entries and deployments through the Configuration API, as {@link EmbeddedOriginApiTest} does:
 * application {@code app} served by plugin {@code p1}, and targets served by plugin {@code p2}, both of type
 * {@code test-origin}, or by an HTTP origin.
 */
public class EmbeddedCallApiTest extends ResourceBaseTest {

    // deployment-origin "Calling a deployment from an origin plugin"

    @Test
    void testCallReachesModel() {
        servedApp();
        servedModel("m2");

        Response response = chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/m2/chat/completions");

        verify(response, 200);
        assertTrue(response.body().contains("from-p2"), response.body());
        List<Request> requests = TestOriginPluginProvider.REQUESTS;
        assertEquals(2, requests.size());
        Request target = requests.get(1);
        assertEquals(HttpMethod.POST, target.method());
        assertEquals("/openai/deployments/m2/chat/completions", target.uri());
        assertTrue(target.body().toString().contains("\"messages\""), target.body().toString());
    }

    @Test
    void testCallToModelBehindHttpOrigin() {
        servedApp();
        verify(adminPut("/v1/models/platform/m3", """
                {"type": "chat", "endpoint": "http://localhost:4870/openai/deployments/m3/chat/completions"}
                """), 200);
        AtomicReference<RecordedRequest> seen = new AtomicReference<>();

        try (TestWebServer server = new TestWebServer(4870)) {
            server.map(HttpMethod.POST, "/openai/deployments/m3/chat/completions", request -> {
                seen.set(request);
                return TestWebServer.createResponse(200, CHAT_ANSWER, "content-type", "application/json");
            });

            verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                    "X-Test-Call", "/openai/deployments/m3/chat/completions"), 200);
        }

        RecordedRequest origin = seen.get();
        assertNotNull(origin);
        assertTrue(origin.getBody().readUtf8().contains("\"messages\""));
        String key = origin.getHeader("api-key");
        assertNotNull(key);
        assertNotEquals("proxyKey1", key);
        assertNotEquals(TestOriginPluginProvider.REQUESTS.get(0).headers().get("API-KEY"), key);
        String traceparent = origin.getHeader("traceparent");
        assertNotNull(traceparent);
        String traceId = rowOf(awaitAnalyticsRows(2), "app").getTraceId();
        assertTrue(traceparent.contains(traceId), traceparent + " / " + traceId);
    }

    @Test
    void testUnknownTarget() {
        servedApp();

        Response response = chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/missing/chat/completions");

        assertEquals(404, response.status(), response.body());
    }

    @Test
    void testTargetTheCallerMayNotUse() {
        servedApp();
        plugin("p2", GREETING_P2);
        verify(adminPut("/v1/models/platform/m-roles", """
                {"type": "chat", "userRoles": ["nobody-has-this-role"],
                 "interfaces": {"openaiChatCompletions": {"plugin": "p2"}}}
                """), 200);

        Response response = chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/m-roles/chat/completions");

        assertEquals(403, response.status(), response.body());
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size());
    }

    @Test
    void testTargetWithInterceptor() {
        servedApp();
        plugin("p2", GREETING_P2);
        verify(adminPut("/v1/models/platform/m-int", """
                {"type": "chat", "interceptors": ["interceptor1"],
                 "interfaces": {"openaiChatCompletions": {"plugin": "p2"}}}
                """), 200);
        AtomicReference<String> seen = new AtomicReference<>();

        try (TestWebServer server = new TestWebServer(4088)) {
            server.map(HttpMethod.POST, "/api/v1/interceptor/handle", request -> {
                seen.set(request.getBody().readUtf8());
                return TestWebServer.createResponse(200, CHAT_ANSWER, "content-type", "application/json");
            });

            verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                    "X-Test-Call", "/openai/deployments/m-int/chat/completions"), 200);
        }

        assertNotNull(seen.get());
        assertTrue(seen.get().contains("\"messages\""), seen.get());
    }

    @Test
    void testResponsesAndMessagesCalls() {
        servedApp("openaiResponses", "anthropicMessages");
        servedModel("m2", "openaiResponses", "anthropicMessages");

        verify(chat("/openai/v1/responses", "{\"model\":\"app\",\"input\":\"hi\"}",
                "X-Test-Call", "/openai/v1/responses", "X-Test-Call-Model", "m2"), 200);
        verify(chat("/anthropic/v1/messages", "{\"model\":\"app\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "X-Test-Call", "/anthropic/v1/messages", "X-Test-Call-Model", "m2"), 200);

        List<Request> requests = TestOriginPluginProvider.REQUESTS;
        assertEquals(4, requests.size());
        assertEquals("/openai/v1/responses", requests.get(1).uri());
        assertEquals("/anthropic/v1/messages", requests.get(3).uri());
    }

    @Test
    void testTargetFailsMidStream() {
        servedApp();
        verify(adminPut("/v1/models/platform/m3", """
                {"type": "chat", "endpoint": "http://localhost:4871/openai/deployments/m3/chat/completions"}
                """), 200);
        StringBuilder events = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            events.append("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"created\":0,\"model\":\"m\","
                    + "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"part").append(i).append("\"}}]}\n\n");
        }
        events.append("data: [DONE]\n\n");

        try (TestWebServer server = new TestWebServer(4871)) {
            server.map(HttpMethod.POST, "/openai/deployments/m3/chat/completions", new MockResponse()
                    .setResponseCode(200)
                    .setHeader("content-type", "text/event-stream")
                    .setBody(events.toString())
                    .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY));

            HttpResponse<InputStream> response = streamed(STREAM_BODY, "X-Test-Call", "/openai/deployments/m3/chat/completions");
            assertEquals(200, response.statusCode());
            StringBuilder read = new StringBuilder();
            try (InputStream body = response.body()) {
                byte[] chunk = new byte[1024];
                for (int n = body.read(chunk); n > 0; n = body.read(chunk)) {
                    read.append(new String(chunk, 0, n));
                }
            } catch (IOException e) {
                // the connection closed before the answer completed
            }
            assertTrue(read.toString().contains("part0"), read.toString());
            assertFalse(read.toString().contains("[DONE]"), read.toString());
        }
    }

    @Test
    void testCallOutsideTheCreateOperations() {
        servedApp();
        servedModel("m2", "openaiResponses");

        Response list = chat("/openai/deployments/app/chat/completions", CHAT_BODY, "X-Test-Call", "/v1/ops/resource/share/list");
        Response background = chat("/openai/deployments/app/chat/completions",
                "{\"background\":true,\"input\":\"hi\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "X-Test-Call", "/openai/v1/responses", "X-Test-Call-Model", "m2");

        for (Response response : List.of(list, background)) {
            assertEquals(502, response.status(), response.body());
            assertTrue(response.body().contains("No route"), response.body());
        }
        assertEquals(2, TestOriginPluginProvider.REQUESTS.size());
        analyticsRows.forEach(row -> assertEquals("app", row.getDeploymentName()));
    }

    // deployment-origin "Depth limit of calls from an origin plugin"

    @Test
    void testPluginCallingItsOwnDeployment() {
        servedApp();

        Response response = chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/app/chat/completions", "X-Test-Call-Again", "true");

        assertEquals(400, response.status(), response.body());
        assertTrue(response.body().contains("Call depth limit reached: 32"), response.body());
        assertEquals(32, TestOriginPluginProvider.REQUESTS.size());
    }

    // deployment-origin "Calls end with the plugin's pass"

    @Test
    @SneakyThrows
    void testCallAfterThePluginsPassEnded() {
        servedApp();
        servedModel("m2");
        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);
        // Core saves the row and ends the pass in one task, so the pass has ended once the row is there
        awaitAnalyticsRows(1);

        TestOriginPluginProvider.Host kept = TestOriginPluginProvider.HOSTS.get(0);
        CompletableFuture<com.epam.aidial.core.plugin.Response> answer = new CompletableFuture<>();
        kept.context().runOnContext(ignored -> kept.host()
                .call(new Call("/openai/deployments/m2/chat/completions", MultiMap.caseInsensitiveMultiMap(),
                        ProxyUtil.MAPPER.createObjectNode().put("model", "m2")))
                .onComplete(result -> {
                    if (result.succeeded()) {
                        answer.complete(result.result());
                    } else {
                        answer.completeExceptionally(result.cause());
                    }
                }));

        assertEquals(401, answer.get(5, TimeUnit.SECONDS).statusCode());
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size());
    }

    // deployment-origin "Cancelling a call"

    @Test
    @SneakyThrows
    void testClientGoneWhileCallStreams() {
        servedApp();
        servedApp2();

        HttpResponse<InputStream> response = streamed(STREAM_BODY,
                "X-Test-Call", "/openai/deployments/app2/chat/completions", "X-Test-Hold", "true");
        try (InputStream body = response.body()) {
            assertEquals(200, response.statusCode());
            byte[] first = new byte[16];
            assertTrue(body.read(first) > 0);
        }

        // the relay of p1 and the answer of p2
        awaitCondition(() -> TestOriginPluginProvider.CANCELLED.get() == 2);
    }

    @Test
    void testPluginCancelsCall() {
        servedApp();
        servedApp2();

        Response response = chat("/openai/deployments/app/chat/completions", STREAM_BODY,
                "X-Test-Call", "/openai/deployments/app2/chat/completions", "X-Test-Call-Cancel", "true", "X-Test-Hold", "true");

        verify(response, 200);
        assertTrue(response.body().contains("\"content\":\"ok\""), response.body());
        awaitCondition(() -> TestOriginPluginProvider.CANCELLED.get() == 1);
    }

    // app-to-model-accounting "In-process child pass"

    @Test
    @SneakyThrows
    void testTargetLimitsCharged() {
        servedApp();
        servedModel(LIMITED_MODEL);

        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/" + LIMITED_MODEL + "/chat/completions"), 200);

        Response limits = send(HttpMethod.GET, "/v1/deployments/" + LIMITED_MODEL + "/limits", null, null);
        verify(limits, 200);
        assertEquals(15, ProxyUtil.MAPPER.readTree(limits.body()).path("minuteTokenStats").path("used").asInt(), limits.body());
    }

    @Test
    @SneakyThrows
    void testUsageRolledUpToThePluginsPass() {
        servedApp();
        servedModel(LIMITED_MODEL);

        Response response = chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/" + LIMITED_MODEL + "/chat/completions");

        verify(response, 200);
        JsonNode usage = ProxyUtil.MAPPER.readTree(response.body()).path("statistics").path("usage_per_model");
        assertEquals(1, usage.size(), response.body());
        assertEquals(LIMITED_MODEL, usage.get(0).path("model").asText(), response.body());
        assertEquals(15, usage.get(0).path("total_tokens").asInt(), response.body());
    }

    @Test
    void testChildRow() {
        servedApp();
        servedModel(LIMITED_MODEL);

        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/" + LIMITED_MODEL + "/chat/completions"), 200);

        List<AnalyticsLogContext> rows = awaitAnalyticsRows(2);
        assertEquals(2, rows.size());
        AnalyticsLogContext app = rowOf(rows, "app");
        AnalyticsLogContext model = rowOf(rows, LIMITED_MODEL);
        assertEquals("app", model.getParentDeployment());
        assertEquals(List.of("app", LIMITED_MODEL), model.getExecutionPath());
        assertEquals(app.getTraceId(), model.getTraceId());
        assertEquals(app.getSpanId(), model.getParentSpanId());
    }

    // analytics-log "Rows without bodies for an in-process child pass"

    @Test
    void testBodiesLoggedOnce() {
        servedApp();
        servedModel(LIMITED_MODEL);

        verify(chat("/openai/deployments/app/chat/completions", STREAM_BODY,
                "X-Test-Call", "/openai/deployments/" + LIMITED_MODEL + "/chat/completions"), 200);

        List<AnalyticsLogContext> rows = awaitAnalyticsRows(2);
        AnalyticsLogContext model = rowOf(rows, LIMITED_MODEL);
        assertNull(model.getRequestBody());
        assertNull(model.getResponseBody());
        assertNull(model.getAssembledStreamingResponse());
        assertTrue(model.isBodiesOmitted());
        assertEquals(15, model.getTokenUsage().getTotalTokens());
        assertNotNull(model.getTraceId());
        AnalyticsLogContext app = rowOf(rows, "app");
        assertTrue(app.getRequestBody().toString().contains("\"messages\""));
        assertTrue(app.getResponseBody().toString().contains("[DONE]"));
    }

    // deployment-origin "Client gone before the plugin answers"

    @Test
    @SneakyThrows
    void testPluginToldClientGone() {
        servedApp();

        try (Socket socket = openRequest("/openai/deployments/app/chat/completions", CHAT_BODY, "X-Test-Await-Cancel", "true")) {
            awaitCondition(() -> TestOriginPluginProvider.REQUESTS.size() == 1);
        }

        awaitCondition(() -> TestOriginPluginProvider.TOLD_CANCELLED.get() == 1);
    }

    @Test
    @SneakyThrows
    void testNoRetryAfterCancelledPluginFails() {
        plugin("p1", "{}");
        verify(adminPut("/v1/models/platform/" + LIMITED_MODEL, """
                {"type": "chat", "maxRetryAttempts": 2,
                 "upstreams": [{"endpoint": "http://cloud-a", "key": "a"}, {"endpoint": "http://cloud-b", "key": "b"}],
                 "interfaces": {"openaiChatCompletions": {"plugin": "p1"}}}
                """), 200);

        try (Socket socket = openRequest("/openai/deployments/" + LIMITED_MODEL + "/chat/completions", CHAT_BODY,
                "X-Test-Await-Cancel", "true")) {
            awaitCondition(() -> TestOriginPluginProvider.REQUESTS.size() == 1);
        }
        awaitCondition(() -> TestOriginPluginProvider.TOLD_CANCELLED.get() == 1);

        // a retry would reach the plugin again at once
        Thread.sleep(500);
        assertEquals(1, TestOriginPluginProvider.REQUESTS.size());
        assertTrue(analyticsRows.isEmpty(), () -> analyticsRows.stream().map(AnalyticsLogContext::getDeploymentName).toList().toString());
    }

    @Test
    @SneakyThrows
    void testClientGoneReachesTheTargetOfCall() {
        servedApp();
        servedApp2();

        try (Socket socket = openRequest("/openai/deployments/app/chat/completions", CHAT_BODY,
                "X-Test-Call", "/openai/deployments/app2/chat/completions", "X-Test-Await-Cancel", "true")) {
            awaitCondition(() -> TestOriginPluginProvider.REQUESTS.size() == 2);
        }

        // p2, waiting for its answer, is told through the call p1 made
        awaitCondition(() -> TestOriginPluginProvider.TOLD_CANCELLED.get() == 1);
    }

    @Test
    @SneakyThrows
    void testItemOperationEndsItsCalls() {
        servedApp("openaiResponses");
        servedModel("m2");
        Response created = chat("/openai/v1/responses", "{\"model\":\"app\",\"input\":\"hi\"}");
        verify(created, 200);
        String id = ProxyUtil.MAPPER.readTree(created.body()).path("id").asText();
        verify(send(HttpMethod.GET, "/openai/v1/responses/" + id, null, null), 200);

        TestOriginPluginProvider.Host kept = TestOriginPluginProvider.HOSTS.get(TestOriginPluginProvider.HOSTS.size() - 1);
        // the item operation ends a moment after its answer: its calls get 401 from then on
        awaitCondition(() -> callThrough(kept, "/openai/deployments/missing/chat/completions") == 401);
    }

    // deployment-origin "Translator plugin request"

    @Test
    void testTranslatorPluginCallsTheOutputInterface() {
        translatedModel("m-tr", INLINE_TRANSLATOR);
        AtomicReference<RecordedRequest> seen = new AtomicReference<>();
        Response response;

        try (TestWebServer server = new TestWebServer(4870)) {
            server.map(HttpMethod.POST, "/openai/deployments/m-tr/chat/completions", request -> {
                seen.set(request);
                return TestWebServer.createResponse(200, CHAT_ANSWER, "content-type", "application/json");
            });
            response = chat("/anthropic/v1/messages", messages("m-tr"), "X-Test-Call", "/openai/deployments/m-tr/chat/completions");
        }

        verify(response, 200);
        assertTrue(response.body().contains("from-http"), response.body());
        Request translated = TestOriginPluginProvider.REQUESTS.get(0);
        assertEquals("/anthropic/v1/messages", translated.uri());
        assertNotEquals("proxyKey1", translated.headers().get("API-KEY"));
        assertNotNull(seen.get());
        List<AnalyticsLogContext> rows = awaitAnalyticsRows(2);
        assertEquals(1, rows.stream().filter(row -> "m-tr".equals(row.getParentDeployment())).count());
    }

    @Test
    @SneakyThrows
    void testNamedTranslatorWithPlugin() {
        verify(adminPut("/v1/translators/platform/a2c", """
                {"in": "anthropicMessages", "out": "openaiChatCompletions", "plugin": "p1"}
                """), 200);
        Response read = send(HttpMethod.GET, "/v1/translators/platform/a2c", null, "", "authorization", "admin");
        verify(read, 200);
        JsonNode stored = ProxyUtil.MAPPER.readTree(read.body());
        assertEquals("p1", stored.path("plugin").asText(), read.body());
        assertTrue(!stored.has("baseUrl"), read.body());
        translatedModel("m-tr", "\"a2c\"");

        verify(chat("/anthropic/v1/messages", messages("m-tr")), 200);

        assertEquals("/anthropic/v1/messages", TestOriginPluginProvider.REQUESTS.get(0).uri());
    }

    @Test
    void testTranslatorsPluginMissing() {
        translatedModel("m-tr", "{\"out\": \"openaiChatCompletions\", \"plugin\": \"missing\"}");

        Response response = chat("/anthropic/v1/messages", messages("m-tr"));

        assertEquals(502, response.status(), response.body());
        assertTrue(response.body().contains("Failed to connect to upstream server"), response.body());
    }

    // app-to-model-accounting "Translated interface not limit-checked"

    @Test
    @SneakyThrows
    void testTranslatorPluginsCallChargedOnce() {
        translatedModel(LIMITED_MODEL, INLINE_TRANSLATOR);

        try (TestWebServer server = new TestWebServer(4870)) {
            server.map(HttpMethod.POST, "/openai/deployments/" + LIMITED_MODEL + "/chat/completions", 200, CHAT_ANSWER,
                    "content-type", "application/json");
            verify(chat("/anthropic/v1/messages", messages(LIMITED_MODEL),
                    "X-Test-Call", "/openai/deployments/" + LIMITED_MODEL + "/chat/completions"), 200);
        }

        Response limits = send(HttpMethod.GET, "/v1/deployments/" + LIMITED_MODEL + "/limits", null, null);
        verify(limits, 200);
        assertEquals(15, ProxyUtil.MAPPER.readTree(limits.body()).path("minuteTokenStats").path("used").asInt(), limits.body());
    }

    @Test
    void testTranslatorPluginsCallRefusedOverTheLimit() {
        // the default role allows gpt-3-turbo 100 tokens a minute
        translatedModel("gpt-3-turbo", INLINE_TRANSLATOR);
        String spent = CHAT_ANSWER.replace("\"prompt_tokens\":10", "\"prompt_tokens\":145").replace("\"total_tokens\":15", "\"total_tokens\":150");

        try (TestWebServer server = new TestWebServer(4870)) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-3-turbo/chat/completions", 200, spent, "content-type", "application/json");
            verify(chat("/anthropic/v1/messages", messages("gpt-3-turbo"),
                    "X-Test-Call", "/openai/deployments/gpt-3-turbo/chat/completions"), 200);

            Response refused = chat("/anthropic/v1/messages", messages("gpt-3-turbo"),
                    "X-Test-Call", "/openai/deployments/gpt-3-turbo/chat/completions");

            // the call is refused 429; the test plugin answers with that status, and an only origin answering 429
            // is "Service is not available" to the caller, as for an origin over HTTP
            assertEquals(503, refused.status(), refused.body());
            assertEquals("Service is not available", refused.body());
            assertEquals(2, TestOriginPluginProvider.REQUESTS.size());
        }
    }

    // responses-api "Item request to an embedded origin"

    @Test
    @SneakyThrows
    void testItemOperationReachesTranslatorsPlugin() {
        plugin("p1", "{}");
        verify(adminPut("/v1/models/platform/m-tr", """
                {"type": "chat", "endpoint": "http://localhost:4870/openai/deployments/m-tr/chat/completions",
                 "interfaces": {"openaiResponses": {"mode": "translator", "translator": %s}}}
                """.formatted(INLINE_TRANSLATOR)), 200);
        Response created = chat("/openai/v1/responses", "{\"model\":\"m-tr\",\"input\":\"hi\"}");
        verify(created, 200);
        String id = ProxyUtil.MAPPER.readTree(created.body()).path("id").asText();

        verify(send(HttpMethod.GET, "/openai/v1/responses/" + id, null, null), 200);

        List<Request> requests = TestOriginPluginProvider.REQUESTS;
        Request item = requests.get(requests.size() - 1);
        assertEquals(HttpMethod.GET, item.method());
        assertTrue(item.uri().startsWith("/openai/v1/responses/"), item.uri());
    }

    // deployment-interfaces-config "No embedded interceptors"

    @Test
    void testInterceptorNamingTranslatorWithPlugin() {
        plugin("p1", "{}");
        verify(adminPut("/v1/translators/platform/c2r", """
                {"in": "openaiChatCompletions", "out": "openaiResponses", "plugin": "p1"}
                """), 200);
        verify(adminPut("/v1/interceptors/platform/i-tr", """
                {"interfaces": {"openaiChatCompletions": {"mode": "translator", "translator": "c2r"}}}
                """), 200);
        plugin("p2", GREETING_P2);
        verify(adminPut("/v1/models/platform/m-int2", """
                {"type": "chat", "interceptors": ["i-tr"],
                 "interfaces": {"openaiChatCompletions": {"plugin": "p2"}}}
                """), 200);

        Response response = chat("/openai/deployments/m-int2/chat/completions", CHAT_BODY);

        assertEquals(502, response.status(), response.body());
        assertTrue(TestOriginPluginProvider.REQUESTS.isEmpty());
    }

    // deployment-origin "Caller in the plugin request"

    @Test
    void testCallerWithApiKey() {
        servedApp();

        verify(chat("/openai/deployments/app/chat/completions", CHAT_BODY), 200);

        Caller caller = TestOriginPluginProvider.REQUESTS.get(0).caller();
        assertEquals("EPM-RTC-GPT", caller.project());
        assertEquals(List.of("default"), caller.roles());
        assertNull(caller.userId());
    }

    @Test
    void testCallerWithJwt() {
        servedApp();

        verify(send(HttpMethod.POST, "/openai/deployments/app/chat/completions", null, CHAT_BODY,
                "content-type", "application/json", "authorization", "user"), 200);

        Caller caller = TestOriginPluginProvider.REQUESTS.get(0).caller();
        assertEquals("user", caller.userId());
        assertEquals(List.of("user"), caller.roles());
    }

    @BeforeEach
    void resetTestOrigin() {
        TestOriginPluginProvider.reset();
    }

    private static final String CRLF = "\r\n";
    private static final HttpClient CLIENT = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
    // the shared test config gives role default token limits on this model
    private static final String LIMITED_MODEL = "chat-gpt-35-turbo";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String STREAM_BODY = "{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String GREETING_P2 = "{\"greeting\": \"from-p2\"}";
    private static final String INLINE_TRANSLATOR = "{\"out\": \"openaiChatCompletions\", \"plugin\": \"p1\"}";
    private static final String CHAT_ANSWER = """
            {"id":"chatcmpl-1","object":"chat.completion","created":0,"model":"m3",
             "choices":[{"index":0,"message":{"role":"assistant","content":"from-http"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""";

    /**
     * Plugin {@code p1}, and application {@code app} served by it on chat completions and {@code more} interfaces.
     */
    private void servedApp(String... more) {
        plugin("p1", "{}");
        verify(adminPut("/v1/applications/platform/app", interfaces("p1", more)), 200);
    }

    /**
     * Plugin {@code p2}, and application {@code app2} served by it on chat completions.
     */
    private void servedApp2() {
        plugin("p2", GREETING_P2);
        verify(adminPut("/v1/applications/platform/app2", interfaces("p2")), 200);
    }

    /**
     * Plugin {@code p2}, and platform model {@code name} served by it on chat completions and {@code more} interfaces.
     */
    private void servedModel(String name, String... more) {
        plugin("p2", GREETING_P2);
        String body = interfaces("p2", more);
        verify(adminPut("/v1/models/platform/" + name, "{\"type\": \"chat\", " + body.substring(body.indexOf('{') + 1)), 200);
    }

    /**
     * Plugin {@code p1}, and platform model {@code name} behind the HTTP origin at port 4870 for chat completions, whose
     * Messages interface is translated by {@code translator}: a name in quotes, or an inline definition.
     */
    private void translatedModel(String name, String translator) {
        plugin("p1", "{}");
        verify(adminPut("/v1/models/platform/" + name, """
                {"type": "chat", "endpoint": "http://localhost:4870/openai/deployments/%s/chat/completions",
                 "interfaces": {"anthropicMessages": {"mode": "translator", "translator": %s}}}
                """.formatted(name, translator)), 200);
    }

    private static String messages(String model) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    }

    private static String interfaces(String plugin, String... more) {
        StringBuilder entries = new StringBuilder("\"openaiChatCompletions\": {\"plugin\": \"" + plugin + "\"}");
        for (String type : more) {
            entries.append(", \"").append(type).append("\": {\"plugin\": \"").append(plugin).append("\"}");
        }
        return "{\"interfaces\": {" + entries + "}}";
    }

    private void plugin(String name, String settings) {
        verify(adminPut("/v1/plugins/platform/" + name, "{\"type\": \"" + TestOriginPluginProvider.TYPE + "\", \"settings\": " + settings + "}"), 200);
    }

    private static AnalyticsLogContext rowOf(List<AnalyticsLogContext> rows, String deployment) {
        return rows.stream().filter(row -> deployment.equals(row.getDeploymentName())).findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + deployment + " in " + rows.stream().map(AnalyticsLogContext::getDeploymentName).toList()));
    }

    @SneakyThrows
    private static int callThrough(TestOriginPluginProvider.Host kept, String uri) {
        CompletableFuture<com.epam.aidial.core.plugin.Response> answer = new CompletableFuture<>();
        kept.context().runOnContext(ignored -> kept.host()
                .call(new Call(uri, MultiMap.caseInsensitiveMultiMap(), ProxyUtil.MAPPER.createObjectNode().put("model", "m2")))
                .onComplete(result -> {
                    if (result.succeeded()) {
                        result.result().cancel();
                        answer.complete(result.result());
                    } else {
                        answer.completeExceptionally(result.cause());
                    }
                }));
        return answer.get(5, TimeUnit.SECONDS).statusCode();
    }

    /**
     * A request sent over a socket of its own, so that closing the socket is a client going away at once.
     */
    @SneakyThrows
    private Socket openRequest(String path, String body, String... headers) {
        Socket socket = new Socket("localhost", serverPort);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder("POST " + path + " HTTP/1.1" + CRLF + "Host: localhost" + CRLF + "api-key: proxyKey1" + CRLF
                + "content-type: application/json" + CRLF + "content-length: " + bytes.length + CRLF);
        for (int i = 0; i < headers.length; i += 2) {
            head.append(headers[i]).append(": ").append(headers[i + 1]).append(CRLF);
        }
        OutputStream out = socket.getOutputStream();
        out.write(head.append(CRLF).toString().getBytes(StandardCharsets.UTF_8));
        out.write(bytes);
        out.flush();
        return socket;
    }

    /**
     * A streamed chat completion to {@code app}, its body read as it arrives. Closing the body closes the connection.
     */
    @SneakyThrows
    private HttpResponse<InputStream> streamed(String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/openai/deployments/app/chat/completions"))
                .version(HttpClient.Version.HTTP_1_1)
                .header("api-key", "proxyKey1")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        return CLIENT.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
    }

    private Response chat(String path, String body, String... headers) {
        String[] all = new String[headers.length + 2];
        all[0] = "content-type";
        all[1] = "application/json";
        System.arraycopy(headers, 0, all, 2, headers.length);
        return send(HttpMethod.POST, path, null, body, all);
    }

    private Response adminPut(String path, String body) {
        return send(HttpMethod.PUT, path, null, body, "authorization", "admin");
    }
}
