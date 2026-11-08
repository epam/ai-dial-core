package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.RouterDecision;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.service.BackgroundJobService;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static com.google.common.collect.MoreCollectors.onlyElement;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A router in decision mode: Core asks it which deployment serves the request and serves that one itself, in the
 * one pass the client pays for.
 */
class RouterDecisionApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/router-decision.json";
    private static final String INTERCEPTOR_CONFIG = "dial-config/router-decision-interceptors.json";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";
    private static final String COMPLETION = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}}";
    private static final String MODEL_COMPLETION = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"model\":\"gpt-4-real\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":10,\"total_tokens\":15}}";
    private static final String MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String EMBEDDINGS = "{\"object\":\"list\",\"data\":[],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
    private static final String RESPONSE = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String QUEUED = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"queued\",\"output\":[]}";
    private static final String COMPLETED = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String DEPLOYMENT_ID = "X-DIAL-DEPLOYMENT-ID";

    private final List<RecordedRequest> received = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> verdict = new AtomicReference<>("{\"candidates\": [\"gpt-4\"]}");

    @Test
    @DialConfigLocation(CONFIG)
    void headersAndEmptyBody() throws Exception {
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "X-CONVERSATION-ID", "c1", "content-type", "application/json"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        assertEquals("POST", decide.getMethod());
        assertEquals("c1", decide.getHeader("X-CONVERSATION-ID"));
        assertEquals("medium", decide.getHeader("X-DIAL-ROUTER-TIER"));
        assertEquals("r1", decide.getHeader("X-DIAL-ROUTER"));
        assertEquals("openaiChatCompletions", decide.getHeader("X-DIAL-API"));
        assertNotNull(decide.getHeader("api-key"));
        assertNotEquals("proxyKey1", decide.getHeader("api-key"));
        assertEquals(0, decide.getBodySize());

        RecordedRequest model = single(request -> request.getPath().startsWith("/openai/deployments/gpt-4-real/"));
        assertNull(model.getHeader("X-DIAL-ROUTER-TIER"));
        assertEquals("c1", model.getHeader("X-CONVERSATION-ID"));
    }

    @Test
    @DialConfigLocation("dial-config/routers-typed.json")
    void typedRouterDecideRequestCarriesTheProperties() throws Exception {
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r4/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        assertEquals(ProxyUtil.MAPPER.readTree("{\"tier\": \"medium\", \"preferredModels\": [\"gpt-4\"]}"),
                ProxyUtil.MAPPER.readTree(decide.getHeader("X-DIAL-APPLICATION-PROPERTIES")));
        RecordedRequest model = single(request -> request.getPath().startsWith("/openai/deployments/gpt-4/"));
        assertNull(model.getHeader("X-DIAL-APPLICATION-PROPERTIES"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void routerAsksModelWhileDeciding() {
        List<AnalyticsLogContext> rows = captureRows();
        AtomicReference<String> decideKey = new AtomicReference<>();
        AtomicInteger classification = new AtomicInteger();
        AtomicLong ttlMs = new AtomicLong();
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (!isDecide(request)) {
                return answer(request);
            }
            String key = request.getHeader("api-key");
            decideKey.set(key);
            // the record's name carries the storage prefix; its last segment is the key
            String name = redis().getKeys().getKeysStreamByPattern("*" + key).findFirst().orElseThrow();
            ttlMs.set(redis().getBucket(name).remainTimeToLive());
            // a classifier call, made with the decide request's key before the router answers
            classification.set(send(HttpMethod.POST, "/openai/deployments/m1/chat/completions", null, CHAT_BODY,
                    "api-key", key, "content-type", "application/json").status());
            return TestWebServer.createResponse(200, "{\"candidates\": [\"gpt-4\"]}", "content-type", "application/json");
        })) {
            Response response = sendChat("r1");
            verify(response, 200);
            JsonNode usage = ProxyUtil.MAPPER.readTree(response.body()).path("statistics").path("usage_per_model");
            List<String> models = new ArrayList<>();
            usage.forEach(entry -> models.add(entry.path("model").asText()));
            assertTrue(models.contains("m1"), response.body());
        }
        assertEquals(200, classification.get());
        // r1 has the default 5 s decision budget: the key lives that long plus a minute, not the default 30 minutes
        assertTrue(ttlMs.get() > 0 && ttlMs.get() <= 5_000 + 60_000, "decide key TTL " + ttlMs.get());
        RecordedRequest served = single(request -> request.getPath().startsWith("/openai/deployments/gpt-4-real/"));
        assertNotEquals(decideKey.get(), served.getHeader("api-key"));

        awaitRows(rows, 3);
        AnalyticsLogContext classifier = row(rows, "m1");
        assertEquals("r1", classifier.getParentDeployment());
        // the router row counts the call made while deciding with the one that served
        AnalyticsLogContext router = routerRow(rows);
        BigDecimal both = classifier.getTokenUsage().getCost().add(row(rows, "gpt-4").getTokenUsage().getCost());
        assertEquals(0, both.compareTo(router.getTokenUsage().getAggCost()), () -> router.getTokenUsage().toString());
        assertEquals(List.of("m1", "gpt-4"), router.getUsagePerModel().stream().map(UsagePerModel::getModel).toList());
        // the priced call made while deciding is no cost a router accrues as an aggregated cost
        assertEquals(List.of(), redis().getKeys().getKeysStreamByPattern("*aggregated-costs*").toList());

        // the key is invalidated when the decide call ends; the delete is not awaited, so allow it a moment
        int status = 0;
        for (int i = 0; i < 50 && status != 401; i++) {
            status = send(HttpMethod.GET, "/v1/user/info", null, null, "api-key", decideKey.get()).status();
            if (status != 401) {
                Thread.sleep(20);
            }
        }
        assertEquals(401, status);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void deploymentClosedToPerRequestKeysRefusesTheDecideKey() {
        AtomicInteger closed = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (!isDecide(request)) {
                return answer(request);
            }
            closed.set(send(HttpMethod.POST, "/openai/deployments/m-closed/chat/completions", null, CHAT_BODY,
                    "api-key", request.getHeader("api-key"), "content-type", "application/json").status());
            return TestWebServer.createResponse(200, "{\"candidates\": [\"gpt-4\"]}", "content-type", "application/json");
        })) {
            verify(sendChat("r1"), 200);
        }
        assertEquals(403, closed.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void bodyOnRequest() {
        verdict.set("{\"candidates\": [\"m1\"]}");
        String body = "{\"model\":\"r1\",\"messages\":[]}";
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r3/chat/completions", null, body,
                    "content-type", "application/json"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        // the interface's base_url stands in for the router's baseUrl
        assertEquals("/body/decide", decide.getPath());
        assertEquals(body, decide.getBody().readUtf8());
        assertEquals("application/json", decide.getHeader("content-type"));
        single(request -> request.getPath().equals("/openai/deployments/m1/chat/completions"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void decideKeyReadsTheAttachmentsOfTheBodyItCarries() {
        String file = "files/" + bucket + "/note.txt";
        verify(upload(HttpMethod.PUT, "/v1/" + file, null, "hello"), 200);
        String body = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\",\"custom_content\":{\"attachments\":[{\"url\":\"" + file + "\"}]}}]}";
        AtomicInteger read = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (!isDecide(request)) {
                return answer(request);
            }
            read.set(send(HttpMethod.GET, "/v1/" + file, null, null, "api-key", request.getHeader("api-key")).status());
            return TestWebServer.createResponse(200, verdict.get(), "content-type", "application/json");
        })) {
            verify(send(HttpMethod.POST, "/openai/deployments/r3/chat/completions", null, body, "content-type", "application/json"), 200);
        }
        assertEquals(200, read.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void decisionInterfaceIsNotProxied() {
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        assertEquals("/decide", decide.getPath());
        assertEquals(0, decide.getBodySize());
        assertEquals(2, received.size());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void shorthandAccepted() {
        verdict.set("{\"candidates\": [\"gpt-4\"], \"ttl\": 60}");
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200, COMPLETION);
        }
        single(request -> request.getPath().equals("/openai/deployments/gpt-4-real/chat/completions"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void schemaMismatch() {
        try (TestWebServer server = server()) {
            for (String answer : List.of("{\"candidates\": []}", "{\"deployment\": \"gpt-4\"}", "{\"model\": \"gpt-4\"}", "not json")) {
                verdict.set(answer);
                verify(sendChat("r1"), 502);
            }
        }
        assertEquals(4, received.size());
        assertTrue(received.stream().allMatch(this::isDecide));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void deploymentsPostServedByTheChosenModel() {
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200, COMPLETION);
        }
        RecordedRequest model = single(request -> !isDecide(request));
        assertEquals("/openai/deployments/gpt-4-real/chat/completions", model.getPath());
        assertNotNull(model.getHeader("api-key"));
        assertNotEquals("proxyKey1", model.getHeader("api-key"));
        assertEquals("gpt-4-real", model.getHeader("X-DIAL-OVERRIDE-NAME"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void chatCompletionsApiServedByTheChosenModel() throws Exception {
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                    "{\"model\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "content-type", "application/json"), 200);
        }
        RecordedRequest model = single(request -> !isDecide(request));
        assertEquals("gpt-4-real", ProxyUtil.MAPPER.readTree(model.getBody().readUtf8()).get("model").asText());
        assertEquals(1, received.stream().filter(this::isDecide).count());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void chatCompletionsApiBodyModelIsTheCandidateWithoutOverrideName() throws Exception {
        verdict.set("{\"candidates\": [\"m1\"]}");
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/v1/chat/completions", null,
                    "{\"model\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "content-type", "application/json"), 200);
        }
        RecordedRequest model = single(request -> !isDecide(request));
        assertEquals("m1", ProxyUtil.MAPPER.readTree(model.getBody().readUtf8()).get("model").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void responsesApiServedByTheChosenModel() throws Exception {
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"r3\",\"store\":false,\"input\":\"hi\"}", "content-type", "application/json"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        assertEquals("openaiResponses", decide.getHeader("X-DIAL-API"));
        RecordedRequest model = single(request -> !isDecide(request));
        assertEquals("/openai/v1/responses", model.getPath());
        assertEquals("gpt-4-real", ProxyUtil.MAPPER.readTree(model.getBody().readUtf8()).get("model").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void backgroundResponsesJobDecidedAndRecorded() throws Exception {
        List<AnalyticsLogContext> rows = captureRows();
        try (TestWebServer server = server()) {
            Response response = send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"r3\",\"background\":true,\"input\":\"hi\"}", "content-type", "application/json");
            verify(response, 200);
            assertEquals("queued", ProxyUtil.MAPPER.readTree(response.body()).get("status").asText());
            RecordedRequest create = single(request -> !isDecide(request) && "POST".equals(request.getMethod()));
            assertEquals("gpt-4-real", ProxyUtil.MAPPER.readTree(create.getBody().readUtf8()).get("model").asText());

            // both rows are written when the poll, through Core, finds the origin's answer terminal
            awaitRows(rows, 2);
        }
        AnalyticsLogContext row = row(rows, "gpt-4");
        assertEquals(List.of("r3", "gpt-4"), row.getExecutionPath());
        assertEquals("r3", row.getParentDeployment());
        AnalyticsLogContext router = routerRow(rows);
        assertEquals("r3", router.getDeploymentName());
        assertEquals(router.getSpanId(), row.getParentSpanId());
        assertEquals("r3", router.getDecision().router());
        assertEquals("openaiResponses", router.getDecision().api());
        assertEquals("gpt-4", router.getDecision().deployment());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void messagesBodyModelRewritten() throws Exception {
        verdict.set("{\"candidates\": [\"claude\"]}");
        try (TestWebServer server = server()) {
            verify(sendMessages("r1"), 200);
        }
        RecordedRequest model = single(request -> !isDecide(request));
        assertEquals("/anthropic/v1/messages", model.getPath());
        assertEquals("claude", ProxyUtil.MAPPER.readTree(model.getBody().readUtf8()).get("model").asText());
        assertEquals("claude", model.getHeader(DEPLOYMENT_ID));
        assertEquals("anthropicMessages", single(this::isDecide).getHeader("X-DIAL-API"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void countTokensDecidedAndCountedOnTheChosenModel() {
        verdict.set("{\"candidates\": [\"claude\"]}");
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/anthropic/v1/messages/count_tokens", recording("{\"input_tokens\":5}"));
            verify(send(HttpMethod.POST, "/anthropic/v1/messages/count_tokens", null,
                    "{\"model\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "content-type", "application/json"), 200);
        }
        RecordedRequest model = single(request -> !isDecide(request));
        assertEquals("/anthropic/v1/messages/count_tokens", model.getPath());
        assertEquals("claude", model.getHeader(DEPLOYMENT_ID));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void chosenModelsLimitsCheckedAgainstTheCaller() {
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200);
            verify(sendChat("r1"), 429);
        }
        assertEquals(1, received.stream().filter(request -> !isDecide(request)).count());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void candidateIsRouter() {
        verdict.set("{\"candidates\": [\"r2\"]}");
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 502);
        }
        assertTrue(received.stream().allMatch(this::isDecide));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void bufferedChatAnswerNamesTheRouter() throws Exception {
        List<AnalyticsLogContext> rows = captureRows();
        Response response;
        try (TestWebServer server = upstreamAnswering(MODEL_COMPLETION, "application/json")) {
            response = sendChat("r1");
            verify(response, 200);
        }
        assertEquals("r1", ProxyUtil.MAPPER.readTree(response.body()).get("model").asText());
        awaitRows(rows, 2);
        row(rows, "gpt-4");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void streamedChatAnswerNamesTheRouter() throws Exception {
        String stream = "data: " + MODEL_COMPLETION.replace("chat.completion", "chat.completion.chunk") + "\n\n"
                + "data: [DONE]\n\n";
        HttpResponse<String> response;
        try (TestWebServer server = upstreamAnswering(stream, "text/event-stream")) {
            response = rawStream("r1");
        }
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"model\":\"r1\""), response.body());
        assertFalse(response.body().contains("gpt-4-real"), response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void responsesAnswerNamesTheRouter() throws Exception {
        Response response;
        try (TestWebServer server = upstreamAnswering("{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-4-real\","
                + "\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}", "application/json")) {
            response = send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"r3\",\"input\":\"hi\"}",
                    "content-type", "application/json");
            verify(response, 200);
        }
        assertEquals("r3", ProxyUtil.MAPPER.readTree(response.body()).get("model").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void streamedMessagesAnswerNamesTheRouter() {
        verdict.set("{\"candidates\": [\"claude\"]}");
        String stream = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"model\":\"claude-real\",\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}}\n\n"
                + "event: message_stop\n"
                + "data: {\"type\":\"message_stop\"}\n\n";
        Response response;
        try (TestWebServer server = upstreamAnswering(stream, "text/event-stream")) {
            response = send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"r1\",\"stream\":true,\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json");
            verify(response, 200);
        }
        assertTrue(response.body().contains("\"model\":\"r1\""), response.body());
        assertFalse(response.body().contains("claude-real"), response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void embeddingsKeepTheUpstreamModel() throws Exception {
        verdict.set("{\"candidates\": [\"ada\"]}");
        Response response;
        try (TestWebServer server = upstreamAnswering("{\"object\":\"list\",\"model\":\"ada-real\",\"data\":[],"
                + "\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}", "application/json")) {
            response = send(HttpMethod.POST, "/openai/deployments/r1/embeddings", null, "{\"input\":\"hi\"}",
                    "content-type", "application/json");
            verify(response, 200);
        }
        assertEquals("ada-real", ProxyUtil.MAPPER.readTree(response.body()).get("model").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void undecidedAnswerUnchanged() throws Exception {
        Response response;
        try (TestWebServer server = upstreamAnswering(MODEL_COMPLETION, "application/json")) {
            response = sendChat("gpt-4");
            verify(response, 200);
        }
        assertEquals("gpt-4-real", ProxyUtil.MAPPER.readTree(response.body()).get("model").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void bufferedMessagesAnswerNamesTheRouter() throws Exception {
        verdict.set("{\"candidates\": [\"claude\"]}");
        Response response;
        try (TestWebServer server = upstreamAnswering(MESSAGE.replace("\"role\":\"assistant\",", "\"role\":\"assistant\",\"model\":\"claude-real\","),
                "application/json")) {
            response = sendMessages("r1");
            verify(response, 200);
        }
        // the whole rewritten body arrived: its Content-Length (of the gzip-encoded answer here) fits it
        assertEquals("r1", ProxyUtil.MAPPER.readTree(response.body()).get("model").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void streamedResponsesAnswerNamesTheRouter() {
        String stream = "event: response.created\n"
                + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-4-real\","
                + "\"status\":\"in_progress\"}}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-4-real\","
                + "\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}\n\n";
        Response response;
        try (TestWebServer server = upstreamAnswering(stream, "text/event-stream")) {
            response = send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"r3\",\"stream\":true,\"input\":\"hi\"}",
                    "content-type", "application/json");
            verify(response, 200);
        }
        assertTrue(response.body().contains("\"model\":\"r3\""), response.body());
        assertFalse(response.body().contains("gpt-4-real"), response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void storedResponseReadBackNamesTheRouter() throws Exception {
        String answer = "{\"id\":\"resp_1\",\"object\":\"response\",\"model\":\"gpt-4-real\",\"status\":\"completed\",\"output\":[],"
                + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
        Response stored;
        try (TestWebServer server = upstreamAnswering(answer, "application/json")) {
            Response created = send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"r3\",\"store\":true,\"input\":\"hi\"}",
                    "content-type", "application/json");
            verify(created, 200);
            String id = ProxyUtil.MAPPER.readTree(created.body()).get("id").asText();
            stored = send(HttpMethod.GET, "/openai/v1/responses/" + id, null, null);
            verify(stored, 200);
        }
        assertEquals("r3", ProxyUtil.MAPPER.readTree(stored.body()).get("model").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void streamedAnswerThroughTheCandidatesInterceptorNamesTheRouter() throws Exception {
        verdict.set("{\"candidates\": [\"m-int\"]}");
        String stream = "data: " + MODEL_COMPLETION.replace("chat.completion", "chat.completion.chunk").replace("gpt-4-real", "m-int-real") + "\n\n"
                + "data: [DONE]\n\n";
        HttpResponse<String> response;
        try (TestWebServer server = upstreamAnswering(stream, "text/event-stream")) {
            server.map(HttpMethod.POST, "/openai/deployments/i2/chat/completions", request -> {
                Response callback = send(HttpMethod.POST, "/openai/deployments/interceptor/chat/completions", null,
                        request.getBody().readUtf8(), "api-key", request.getHeader("api-key"), "content-type", "application/json");
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "text/event-stream");
            });
            response = rawStream("r1");
        }
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("\"model\":\"r1\""), response.body());
        assertFalse(response.body().contains("m-int-real"), response.body());
    }

    /**
     * Answers decide requests with the verdict and every other request with {@code body}.
     */
    private TestWebServer upstreamAnswering(String body, String contentType) {
        return new TestWebServer(4848, request -> {
            received.add(request);
            return isDecide(request) ? answer(request) : TestWebServer.createResponse(200, body, "content-type", contentType);
        });
    }

    @Test
    @DialConfigLocation(CONFIG)
    void embeddingsDecidedAndServed() {
        verdict.set("{\"candidates\": [\"ada\"]}");
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/embeddings", null, "{\"input\":\"hi\"}",
                    "content-type", "application/json"), 200, EMBEDDINGS);
        }
        assertEquals("openaiEmbeddings", single(this::isDecide).getHeader("X-DIAL-API"));
        single(request -> request.getPath().equals("/openai/deployments/ada/embeddings"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void unknownLastCandidate() {
        verdict.set("{\"candidates\": [\"private-a\", \"no-such-model\"]}");
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 502);
        }
        assertTrue(received.stream().allMatch(this::isDecide));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void unparsableBodyAfterTheDecisionAnsweredOnce() {
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, "not json",
                    "content-type", "application/json"), 400);
        }
        assertEquals(1, received.size());
        assertTrue(isDecide(received.getFirst()));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerReEnteredThroughAnApplication() {
        verdict.set("{\"candidates\": [\"app\"]}");
        AtomicReference<Integer> callbackStatus = new AtomicReference<>();
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/app/chat/completions", request -> {
                received.add(request);
                Response callback = send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                        "api-key", request.getHeader("api-key"), "content-type", "application/json");
                callbackStatus.set(callback.status());
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "application/json");
            });
            verify(sendChat("r1"), 502);
        }
        // the nested call is refused without a decide request of its own; the outer request decides again with
        // app's 502 in the history until no decision is left
        assertEquals(502, callbackStatus.get());
        assertEquals(3, received.stream().filter(this::isDecide).count());
        assertTrue(received.stream().filter(this::isDecide).skip(1)
                .allMatch(decide -> decide.getHeader("X-DIAL-ROUTER-ATTEMPTS").contains("\"deployment\":\"app\",\"status\":502")));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void forbiddenFirstCandidateSkipped() {
        verdict.set("{\"candidates\": [\"private-model\", \"gpt-4\"]}");
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200, COMPLETION);
        }
        single(request -> !isDecide(request) && request.getPath().contains("gpt-4-real"));
        assertEquals(2, received.size());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void forbiddenFirstCandidateSkippedOnMessages() {
        verdict.set("{\"candidates\": [\"private-model\", \"claude\"]}");
        try (TestWebServer server = server()) {
            verify(sendMessages("r1"), 200);
        }
        assertEquals("claude", single(request -> !isDecide(request)).getHeader(DEPLOYMENT_ID));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void failingFirstCandidateSkipped() throws Exception {
        verdict.set("{\"candidates\": [\"m1\", \"m2\"]}");
        List<AnalyticsLogContext> rows = captureRows();
        AtomicReference<String> failedKey = new AtomicReference<>();
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/m1/chat/completions", request -> {
                received.add(request);
                failedKey.set(request.getHeader("api-key"));
                return TestWebServer.createResponse(503, "{\"error\":{\"message\":\"busy\"}}", "content-type", "application/json");
            });
            verify(sendChat("r1"), 200, COMPLETION);
        }
        single(request -> request.getPath().equals("/openai/deployments/m2/chat/completions"));
        verify(send(HttpMethod.GET, "/v1/bucket", null, null, "api-key", failedKey.get()), 401);

        awaitRows(rows, 2);
        AnalyticsLogContext row = row(rows, "m2");
        assertEquals(List.of("r1", "m2"), row.getExecutionPath());
        assertEquals("r1", row.getParentDeployment());
        AnalyticsLogContext router = routerRow(rows);
        assertEquals("r1", router.getDeploymentName());
        assertEquals(List.of("r1"), router.getExecutionPath());
        assertNull(router.getParentDeployment());
        assertTrue(router.isBodiesOmitted());
        assertNull(router.getRequestBody());
        assertNull(router.getUpstreamEndpoint());
        RouterDecision.Row decision = router.getDecision();
        assertEquals("r1", decision.router());
        assertEquals("openaiChatCompletions", decision.api());
        assertEquals(List.of("m1", "m2"), decision.candidates());
        assertEquals("m2", decision.deployment());
        assertEquals(List.of(new RouterDecision.Attempt("m1", 502, "upstream")), decision.attempts());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void failingAliasCandidateSkipped() {
        verdict.set("{\"candidates\": [\"alias-m1\", \"m2\"]}");
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/m1/chat/completions", request -> {
                received.add(request);
                return TestWebServer.createResponse(503, "{\"error\":{\"message\":\"busy\"}}", "content-type", "application/json");
            });
            verify(sendChat("r1"), 200, COMPLETION);
        }
        single(request -> request.getPath().equals("/openai/deployments/m1/chat/completions"));
        single(request -> request.getPath().equals("/openai/deployments/m2/chat/completions"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void lastAliasCandidateRelaysItsTargetAnswer() {
        verdict.set("{\"candidates\": [\"alias-m1\"]}");
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/m1/chat/completions", request -> {
                received.add(request);
                return TestWebServer.createResponse(429, "{\"error\":{\"message\":\"slow down\"}}",
                        "content-type", "application/json", "Retry-After", "30");
            });
            // the target pass answers 503 with Retry-After once its only upstream is throttled; with no candidate
            // and no decision left, the alias relays it rather than its own 502 "No route"
            Response response = sendChat("r1");
            verify(response, 503);
            assertTrue(response.headers().keySet().stream().anyMatch("retry-after"::equalsIgnoreCase), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void failingFirstCandidateSkippedOnMessages() {
        verdict.set("{\"candidates\": [\"m1\", \"m2\"]}");
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if ("m1".equals(request.getHeader(DEPLOYMENT_ID))) {
                return TestWebServer.createResponse(503, "{\"error\":{\"message\":\"busy\"}}", "content-type", "application/json");
            }
            return answer(request);
        })) {
            verify(sendMessages("r1"), 200);
        }
        assertEquals(List.of("m1", "m2"), received.stream().filter(request -> !isDecide(request))
                .map(request -> request.getHeader(DEPLOYMENT_ID)).toList());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void everyCandidateForbidden() {
        verdict.set("{\"candidates\": [\"private-a\", \"private-b\"]}");
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 403);
            verify(sendMessages("r1"), 403);
        }
        // three decisions per request, the router asked again after each forbidden list
        assertEquals(6, received.size());
        assertTrue(received.stream().allMatch(this::isDecide));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void nonRetriableAnswerRelayed() {
        verdict.set("{\"candidates\": [\"m1\", \"m2\"]}");
        String error = "{\"error\":{\"message\":\"bad request\"}}";
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (request.getPath().contains("/m1/") || "m1".equals(request.getHeader(DEPLOYMENT_ID))) {
                return TestWebServer.createResponse(400, error, "content-type", "application/json");
            }
            return answer(request);
        })) {
            verify(sendChat("r1"), 400, error);
            verify(sendMessages("r1"), 400, error);
        }
        assertTrue(received.stream().noneMatch(request -> request.getPath().contains("/m2/") || "m2".equals(request.getHeader(DEPLOYMENT_ID))));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void chosenDeploymentBehindAnInterceptorIsNotRetried() {
        verdict.set("{\"candidates\": [\"m-int\", \"m2\"]}");
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/i2/chat/completions", request -> {
                received.add(request);
                Response callback = send(HttpMethod.POST, "/openai/deployments/interceptor/chat/completions", null,
                        request.getBody().readUtf8(), "api-key", request.getHeader("api-key"), "content-type", "application/json");
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "application/json");
            });
            server.map(HttpMethod.POST, "/openai/deployments/m-int/chat/completions", request -> {
                received.add(request);
                return TestWebServer.createResponse(503, "{\"error\":{\"message\":\"busy\"}}", "content-type", "application/json");
            });
            verify(sendChat("r1"), 502);
        }
        RecordedRequest interceptor = single(request -> request.getPath().equals("/openai/deployments/i2/chat/completions"));
        assertTrue(interceptor.getBodySize() > 0);
        single(request -> request.getPath().equals("/openai/deployments/m-int/chat/completions"));
        assertTrue(received.stream().noneMatch(request -> request.getPath().contains("/m2/")));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void chosenDeploymentBehindAnInterceptorRecordsTheDecision() {
        verdict.set("{\"candidates\": [\"private-model\", \"m-int\"]}");
        List<AnalyticsLogContext> rows = captureRows();
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/i2/chat/completions", request -> {
                Response callback = send(HttpMethod.POST, "/openai/deployments/interceptor/chat/completions", null,
                        request.getBody().readUtf8(), "api-key", request.getHeader("api-key"), "content-type", "application/json");
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "application/json");
            });
            verify(sendChat("r1"), 200, COMPLETION);
        }
        awaitRows(rows, 2);
        AnalyticsLogContext row = row(rows, "m-int");
        assertEquals(List.of("r1", "i2", "m-int"), row.getExecutionPath());
        assertEquals("r1", row.getParentDeployment());
        RouterDecision.Row decision = routerRow(rows).getDecision();
        assertEquals("r1", decision.router());
        assertEquals(List.of("private-model", "m-int"), decision.candidates());
        assertEquals("m-int", decision.deployment());
        assertEquals(List.of(new RouterDecision.Attempt("private-model", 403, "forbidden")), decision.attempts());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void brokenBodyBeforeTheClientGotByte() {
        List<AnalyticsLogContext> rows = captureRows();
        verdict.set("{\"candidates\": [\"m1\", \"m2\"]}");
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/m1/chat/completions", request -> {
                received.add(request);
                return TestWebServer.createResponse(200, COMPLETION, "content-type", "application/json")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
            });
            verify(sendChat("r1"), 200, COMPLETION);
        }
        single(request -> request.getPath().equals("/openai/deployments/m1/chat/completions"));
        single(request -> request.getPath().equals("/openai/deployments/m2/chat/completions"));
        assertEquals(List.of(new RouterDecision.Attempt("m1", 502, "body", true, false)), routerRow(awaitRows(rows, 2)).getDecision().attempts());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void brokenMessagesBodyFallsBack() {
        verdict.set("{\"candidates\": [\"m1\", \"m2\"]}");
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return answer(request);
            }
            MockResponse message = TestWebServer.createResponse(200, MESSAGE, "content-type", "application/json");
            return "m1".equals(request.getHeader(DEPLOYMENT_ID)) ? message.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) : message;
        })) {
            verify(sendMessages("r1"), 200);
        }
        assertEquals(1, received.stream().filter(request -> "m2".equals(request.getHeader(DEPLOYMENT_ID))).count());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void brokenResponsesBodyFallsBack() {
        try (TestWebServer server = brokenGpt4Responses()) {
            verify(send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"r3\",\"input\":\"hi\"}",
                    "content-type", "application/json"), 200);
        }
        assertEquals(1, received.stream().filter(request -> !isDecide(request) && request.getBody().clone().readUtf8().contains("\"m3\"")).count());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void brokenBackgroundCreateIsNotCreatedAgainElsewhere() {
        // the accepted job may be running on the first candidate, so a second one is not started on another
        try (TestWebServer server = brokenGpt4Responses()) {
            verify(send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"r3\",\"background\":true,\"input\":\"hi\"}",
                    "content-type", "application/json"), 502);
        }
        assertTrue(received.stream().noneMatch(request -> !isDecide(request) && request.getBody().clone().readUtf8().contains("\"m3\"")));
    }

    private TestWebServer brokenGpt4Responses() {
        verdict.set("{\"candidates\": [\"gpt-4\", \"m3\"]}");
        return new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return answer(request);
            }
            String body = request.getBody().clone().readUtf8();
            MockResponse response = TestWebServer.createResponse(200, body.contains("\"background\":true") ? QUEUED : RESPONSE,
                    "content-type", "application/json");
            return body.contains("gpt-4-real") ? response.setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY) : response;
        });
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void brokenStreamBeforeItsFirstChunk() {
        verdict.set("{\"candidates\": [\"m1\", \"m2\"]}");
        String stream = "data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n";
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return answer(request);
            }
            if (request.getPath().contains("/m1/")) {
                // headers promise a body that never comes
                return new MockResponse().setResponseCode(200).setBody("").setHeader("content-type", "text/event-stream")
                        .setHeader("Content-Length", "1000").setSocketPolicy(SocketPolicy.DISCONNECT_AT_END);
            }
            return TestWebServer.createResponse(200, stream, "content-type", "text/event-stream");
        })) {
            HttpResponse<String> response = rawStream("r1");
            assertEquals(200, response.statusCode(), response.body());
            assertEquals(1, response.headers().allValues("content-type").size(), response.headers().map().toString());
            // Core's own headers survive dropping the broken answer's
            assertEquals(List.of("*"), response.headers().allValues("access-control-allow-origin"), response.headers().map().toString());
            assertTrue(response.body().contains("[DONE]"), response.body());
        }
        single(request -> request.getPath().contains("/m2/"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void streamBrokenAfterTheFirstChunkNoticesTheRouter() {
        verdict.set("{\"candidates\": [\"m1\"]}");
        StringBuilder stream = new StringBuilder("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hi\"}}]}\n\n");
        for (int i = 0; i < 50; i++) {
            stream.append("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n");
        }
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return answer(request);
            }
            // half of the chunked body arrives, the first event included, then the connection drops
            return new MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
                    .setChunkedBody(stream.toString(), 64).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
        })) {
            try {
                HttpResponse<String> response = rawStream("r1");
                assertTrue(response.body().contains("\"content\":\"hi\""), response.body());
            } catch (IOException expected) {
                // the client sees its connection broken after the first chunk
            }
            RecordedRequest notice = awaitDecide(request -> "true".equals(request.getHeader("X-DIAL-ROUTER-NOTICE")));
            assertNull(notice.getHeader("api-key"));
            assertEquals(0, notice.getBodySize());
            assertTrue(notice.getHeader("X-DIAL-ROUTER-ATTEMPTS").endsWith(
                    "{\"deployment\":\"m1\",\"status\":200,\"reason\":\"stream\",\"received\":true,\"partial\":true}]"),
                    notice.getHeader("X-DIAL-ROUTER-ATTEMPTS"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void clientGoneSendsNoNotice() {
        verdict.set("{\"candidates\": [\"m1\"]}");
        StringBuilder stream = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            stream.append("data: {\"id\":\"c\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"}}]}\n\n");
        }
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return answer(request);
            }
            return new MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream")
                    .setChunkedBody(stream.toString(), 128).throttleBody(256, 100, TimeUnit.MILLISECONDS);
        })) {
            HttpResponse<InputStream> response = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(streamRequest("r1"),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                assertTrue(body.read(new byte[64]) > 0);
            }
            Thread.sleep(1500);
        }
        assertEquals(1, received.stream().filter(this::isDecide).count(), "no notice and no second decide request");
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void routerDecidesAgainWithTheHistory() {
        List<AnalyticsLogContext> rows = captureRows();
        List<String> verdicts = new ArrayList<>(List.of("{\"candidates\": [\"m1\"]}", "{\"candidates\": [\"m2\"]}"));
        List<ApiKeyData> keys = new CopyOnWriteArrayList<>();
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                keys.add(readKey(request.getHeader("api-key")));
                return TestWebServer.createResponse(200, verdicts.removeFirst(), "content-type", "application/json");
            }
            if (request.getPath().contains("/m1/")) {
                return TestWebServer.createResponse(503, "{}", "content-type", "application/json");
            }
            return answer(request);
        })) {
            verify(sendChat("r1"), 200, COMPLETION);
        }
        List<RecordedRequest> decides = received.stream().filter(this::isDecide).toList();
        assertEquals(2, decides.size());
        assertNull(decides.getFirst().getHeader("X-DIAL-ROUTER-ATTEMPTS"));
        assertEquals("[{\"deployment\":\"m1\",\"status\":502,\"reason\":\"upstream\"}]", decides.get(1).getHeader("X-DIAL-ROUTER-ATTEMPTS"));
        assertNotEquals(decides.getFirst().getHeader("api-key"), decides.get(1).getHeader("api-key"));
        assertEquals(List.of("r1"), keys.get(1).getExecutionPath());
        assertEquals("r1", keys.get(1).getSourceDeployment());

        awaitRows(rows, 2);
        row(rows, "m2");
        RouterDecision.Row row = routerRow(rows).getDecision();
        assertEquals(2, row.decisions());
        assertEquals(List.of("m1", "m2"), row.candidates());
        assertEquals("m2", row.deployment());
        assertEquals(List.of(new RouterDecision.Attempt("m1", 502, "upstream")), row.attempts());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void limitReached() {
        verdict.set("{\"candidates\": [\"m1\"]}");
        List<AnalyticsLogContext> rows = captureRows();
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return answer(request);
            }
            return TestWebServer.createResponse(503, "{}", "content-type", "application/json");
        })) {
            verify(sendChat("r-two"), 502);
        }
        assertEquals(2, received.stream().filter(this::isDecide).count());
        assertEquals(2, received.stream().filter(request -> request.getPath().contains("/m1/")).count());

        // no candidate served, so the router row is the only one
        AnalyticsLogContext router = routerRow(awaitRows(rows, 1));
        assertEquals(502, router.getResponseStatusCode());
        assertEquals(2, router.getDecision().decisions());
        assertEquals("m1", router.getDecision().deployment());
        assertEquals(List.of(new RouterDecision.Attempt("m1", 502, "upstream")), router.getDecision().attempts());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void redecideFailureAnswersTheCandidatesFailure() {
        AtomicInteger decides = new AtomicInteger();
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return decides.incrementAndGet() == 1
                        ? TestWebServer.createResponse(200, "{\"candidates\": [\"m1\"]}", "content-type", "application/json")
                        : TestWebServer.createResponse(500, "router broke", "content-type", "text/plain");
            }
            return TestWebServer.createResponse(503, "{}", "content-type", "application/json");
        })) {
            Response response = sendChat("r1");
            verify(response, 502);
            assertFalse(response.body().contains("decide"), response.body());
        }
        assertEquals(2, decides.get());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void refusalInTheSecondVerdictSkipped() {
        List<String> verdicts = new ArrayList<>(List.of("{\"candidates\": [\"m1\"]}", "{\"candidates\": [\"private-model\", \"m2\"]}"));
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return TestWebServer.createResponse(200, verdicts.removeFirst(), "content-type", "application/json");
            }
            if (request.getPath().contains("/m1/")) {
                return TestWebServer.createResponse(503, "{}", "content-type", "application/json");
            }
            return answer(request);
        })) {
            verify(sendChat("r1"), 200, COMPLETION);
        }
        assertTrue(received.stream().noneMatch(request -> request.getPath().contains("/private-model/")));
        single(request -> request.getPath().contains("/m2/"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void bodySentAgainOnRedecide() {
        List<String> verdicts = new ArrayList<>(List.of("{\"candidates\": [\"m1\"]}", "{\"candidates\": [\"m2\"]}"));
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return TestWebServer.createResponse(200, verdicts.removeFirst(), "content-type", "application/json");
            }
            if (request.getPath().contains("/m1/")) {
                return TestWebServer.createResponse(503, "{}", "content-type", "application/json");
            }
            return answer(request);
        })) {
            verify(sendChat("r3"), 200, COMPLETION);
        }
        List<RecordedRequest> decides = received.stream().filter(this::isDecide).toList();
        assertEquals(2, decides.size());
        decides.forEach(decide -> assertEquals(CHAT_BODY, decide.getBody().clone().readUtf8()));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void forgedRouterHeadersDropped() {
        verdict.set("{\"candidates\": [\"gpt-4\"]}");
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "X-DIAL-ROUTER-ATTEMPTS", "[{\"deployment\":\"x\"}]", "X-DIAL-ROUTER-NOTICE", "true",
                    "content-type", "application/json"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        assertNull(decide.getHeader("X-DIAL-ROUTER-ATTEMPTS"));
        assertNull(decide.getHeader("X-DIAL-ROUTER-NOTICE"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void slowRouter() {
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/slow/decide", request -> TestWebServer.createResponse(200, verdict.get(), "content-type", "application/json")
                    .setHeadersDelay(2, TimeUnit.SECONDS));
            long start = System.currentTimeMillis();
            verify(sendChat("r-slow"), 504);
            assertTrue(System.currentTimeMillis() - start < 1000, "answered after " + (System.currentTimeMillis() - start) + " ms");
        }
        assertTrue(received.isEmpty());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerRejectsTheRequest() {
        String error = "{\"error\": {\"message\": \"state either tier or target\"}}";
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/decide", 400, error, "content-type", "application/json");
            Response response = sendChat("r1");
            verify(response, 400, error);
            assertEquals("application/json", response.headers().get("content-type"));
        }
        assertTrue(received.isEmpty());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerDown() {
        try (TestWebServer server = server()) {
            verify(sendChat("r-down"), 502);
        }
        assertTrue(received.isEmpty());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void rootKeyRecordAfterDecision() {
        AtomicReference<ApiKeyData> keyData = new AtomicReference<>();
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/gpt-4-real/chat/completions", request -> {
                received.add(request);
                keyData.set(readKey(request.getHeader("api-key")));
                return TestWebServer.createResponse(200, COMPLETION, "content-type", "application/json");
            });
            verify(sendChat("r1"), 200);
        }
        assertEquals("gpt-4", keyData.get().getSourceDeployment());
        assertEquals(List.of("r1", "gpt-4"), keyData.get().getExecutionPath());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void servingRowIsTheRouterRowsChild() {
        List<AnalyticsLogContext> rows = captureRows();
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200);
        }
        // a decided request adds exactly one row, the router's
        awaitRows(rows, 2);
        AnalyticsLogContext row = row(rows, "gpt-4");
        assertEquals(List.of("r1", "gpt-4"), row.getExecutionPath());
        assertEquals("r1", row.getParentDeployment());
        AnalyticsLogContext router = routerRow(rows);
        assertEquals(List.of(), router.getDecision().attempts());
        assertEquals(router.getSpanId(), row.getParentSpanId());
        assertNotEquals(row.getSpanId(), router.getSpanId());
        assertEquals(row.getTraceId(), router.getTraceId());
        assertNull(router.getParentSpanId());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void streamedBackgroundJobWritesBothRowsLive() {
        List<AnalyticsLogContext> rows = captureRows();
        String stream = "event: response.created\n"
                + "data: {\"type\":\"response.created\",\"response\":{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"queued\"}}\n\n"
                + "event: response.completed\n"
                + "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_1\",\"object\":\"response\","
                + "\"status\":\"completed\",\"output\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1,\"total_tokens\":2}}}\n\n";
        try (TestWebServer server = new TestWebServer(4848, request -> {
            received.add(request);
            if (isDecide(request)) {
                return answer(request);
            }
            return TestWebServer.createResponse(200, stream, "content-type", "text/event-stream");
        })) {
            verify(send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"r3\",\"background\":true,\"stream\":true,\"input\":\"hi\"}", "content-type", "application/json"), 200);
            // the stream ended the job live, so the poller has nothing left to write
            awaitRows(rows, 2);
        }
        assertEquals("gpt-4", routerRow(rows).getDecision().deployment());
        row(rows, "gpt-4");
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerRowShowsTheCostUnderIt() {
        List<AnalyticsLogContext> rows = captureRows();
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200);
        }
        awaitRows(rows, 2);
        AnalyticsLogContext row = row(rows, "gpt-4");
        AnalyticsLogContext router = routerRow(rows);
        assertEquals(1, row.getTokenUsage().getAggCost().signum());
        assertEquals(0, row.getTokenUsage().getAggCost().compareTo(router.getTokenUsage().getAggCost()));
        assertNull(router.getTokenUsage().getCost());
        assertEquals(0, router.getTokenUsage().getTotalTokens());
        assertEquals(List.of("gpt-4"), router.getUsagePerModel().stream().map(UsagePerModel::getModel).toList());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void routerRowOfSecuredKey() {
        List<AnalyticsLogContext> rows = captureRows();
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r1/chat/completions", null, CHAT_BODY,
                    "api-key", "securedKey1", "content-type", "application/json"), 200);
        }
        awaitRows(rows, 2);
        AnalyticsLogContext router = routerRow(rows);
        assertTrue(router.isSecuredApiKey());
        assertEquals("gpt-4", router.getDecision().deployment());
        assertTrue(row(rows, "gpt-4").isSecuredApiKey());
    }

    @Test
    @DialConfigLocation(INTERCEPTOR_CONFIG)
    void globalInterceptorBeforeTheDecision() {
        assertDecidedAfterTheInterceptor("/openai/deployments/r1/chat/completions", CHAT_BODY,
                "/openai/deployments/interceptor/chat/completions", "/openai/deployments/gpt-4/chat/completions");
    }

    @Test
    @DialConfigLocation(INTERCEPTOR_CONFIG)
    void globalInterceptorBeforeTheDecisionOnTheChatCompletionsApi() {
        assertDecidedAfterTheInterceptor("/openai/v1/chat/completions",
                "{\"model\":\"r1\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}", "/openai/v1/chat/completions",
                "/openai/v1/chat/completions");
    }

    @Test
    @DialConfigLocation(INTERCEPTOR_CONFIG)
    void globalInterceptorBeforeTheDecisionOnTheResponsesApi() {
        assertDecidedAfterTheInterceptor("/openai/v1/responses", "{\"model\":\"r1\",\"store\":false,\"input\":\"hi\"}",
                "/i1/openai/v1/responses", "/openai/v1/responses", "/openai/v1/responses", RESPONSE);
    }

    @Test
    @DialConfigLocation(INTERCEPTOR_CONFIG)
    void aliasCandidateAfterGlobalInterceptor() {
        verdict.set("{\"candidates\": [\"alias-gpt\"]}");
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/i1/chat/completions", request -> {
                received.add(request);
                Response callback = send(HttpMethod.POST, "/openai/deployments/interceptor/chat/completions", null,
                        request.getBody().readUtf8(), "api-key", request.getHeader("api-key"), "content-type", "application/json");
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "application/json");
            });
            verify(sendChat("r1"), 200);
        }
        single(request -> request.getPath().equals("/openai/deployments/gpt-4/chat/completions"));
    }

    @Test
    @DialConfigLocation(INTERCEPTOR_CONFIG)
    void routerRowAfterGlobalInterceptor() {
        List<AnalyticsLogContext> rows = captureRows();
        assertDecidedAfterTheInterceptor("/openai/deployments/r1/chat/completions", CHAT_BODY,
                "/openai/deployments/interceptor/chat/completions", "/openai/deployments/gpt-4/chat/completions");
        awaitRows(rows, 2);
        AnalyticsLogContext router = routerRow(rows);
        assertEquals(List.of("i1", "r1"), router.getExecutionPath());
        assertNull(router.getParentDeployment());
        assertEquals(router.getSpanId(), row(rows, "gpt-4").getParentSpanId());
    }

    private void assertDecidedAfterTheInterceptor(String path, String body, String callbackPath, String modelPath) {
        assertDecidedAfterTheInterceptor(path, body, "/openai/deployments/i1/chat/completions", callbackPath, modelPath, COMPLETION);
    }

    private void assertDecidedAfterTheInterceptor(String path, String body, String interceptorPath, String callbackPath, String modelPath,
                                                  String modelAnswer) {
        AtomicReference<ApiKeyData> keyData = new AtomicReference<>();
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, interceptorPath, request -> {
                received.add(request);
                Response callback = send(HttpMethod.POST, callbackPath, null, request.getBody().readUtf8(),
                        "api-key", request.getHeader("api-key"), "content-type", "application/json");
                return TestWebServer.createResponse(callback.status(), callback.body(), "content-type", "application/json");
            });
            server.map(HttpMethod.POST, modelPath, request -> {
                received.add(request);
                keyData.set(readKey(request.getHeader("api-key")));
                return TestWebServer.createResponse(200, modelAnswer, "content-type", "application/json");
            });
            verify(send(HttpMethod.POST, path, null, body, "content-type", "application/json"), 200);
        }
        assertEquals(List.of(interceptorPath, "/decide", modelPath),
                received.stream().map(RecordedRequest::getPath).toList());
        assertEquals(List.of("i1", "r1", "gpt-4"), keyData.get().getExecutionPath());
    }

    private Response sendChat(String deployment) {
        return send(HttpMethod.POST, "/openai/deployments/" + deployment + "/chat/completions", null, CHAT_BODY,
                "content-type", "application/json");
    }

    private Response sendMessages(String model) {
        return send(HttpMethod.POST, "/anthropic/v1/messages", null,
                "{\"model\":\"" + model + "\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                "content-type", "application/json");
    }

    @Override
    protected JsonObject additionalSettingsOverrides() {
        // a decided background job is polled soon enough for a test to see its row
        return new JsonObject().put("backgroundJob", new JsonObject().put("initialPollIntervalMs", 300));
    }

    private TestWebServer server() {
        return new TestWebServer(4848, request -> {
            received.add(request);
            return answer(request);
        });
    }

    private MockResponse answer(RecordedRequest request) {
        String path = request.getPath();
        String body;
        if (isDecide(request)) {
            body = verdict.get();
        } else if (path.startsWith("/anthropic/")) {
            body = MESSAGE;
        } else if (path.endsWith("/embeddings")) {
            body = EMBEDDINGS;
        } else if (path.startsWith("/openai/v1/responses")) {
            // a background create is accepted as queued; the poll that follows finds it completed
            body = "GET".equals(request.getMethod()) ? COMPLETED
                    : request.getBody().clone().readUtf8().contains("\"background\":true") ? QUEUED : RESPONSE;
        } else {
            body = COMPLETION;
        }
        return TestWebServer.createResponse(200, body, "content-type", "application/json");
    }

    private TestWebServer.Handler recording(String body) {
        return request -> {
            received.add(request);
            return TestWebServer.createResponse(200, body, "content-type", "application/json");
        };
    }

    @SneakyThrows
    private RecordedRequest awaitDecide(Predicate<RecordedRequest> filter) {
        // Core does not wait for the notice, so it may trail the client's answer
        for (int i = 0; i < 100; i++) {
            Optional<RecordedRequest> found = received.stream().filter(this::isDecide).filter(filter).findFirst();
            if (found.isPresent()) {
                return found.get();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no such decide request: " + received.stream().map(RecordedRequest::getPath).toList());
    }

    private HttpRequest streamRequest(String router) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + serverPort + "/openai/deployments/" + router + "/chat/completions"))
                .header("api-key", "proxyKey1").header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
    }

    /**
     * A streamed chat completion read with a client that keeps every header, so duplicates show.
     */
    private HttpResponse<String> rawStream(String router) throws IOException, InterruptedException {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build().send(streamRequest(router), HttpResponse.BodyHandlers.ofString());
    }

    private boolean isDecide(RecordedRequest request) {
        return request.getPath().endsWith("/decide");
    }

    private RecordedRequest single(Predicate<RecordedRequest> filter) {
        List<RecordedRequest> matching = received.stream().filter(filter).toList();
        assertEquals(1, matching.size(), () -> received.stream().map(RecordedRequest::getPath).toList().toString());
        return matching.getFirst();
    }

    @SneakyThrows
    private ApiKeyData readKey(String key) {
        return apiKeyStore.getApiKeyData(key, null).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
    }

    @SneakyThrows
    private RedissonClient redis() {
        Field field = AiDial.class.getDeclaredField("redis");
        field.setAccessible(true);
        return (RedissonClient) field.get(dial);
    }

    @SneakyThrows
    private List<AnalyticsLogContext> captureRows() {
        List<AnalyticsLogContext> rows = new CopyOnWriteArrayList<>();
        LogStore store = rows::add;
        Field field = Proxy.class.getDeclaredField("logStore");
        field.setAccessible(true);
        field.set(dial.getProxy(), store);
        // a background job's row is written by the job service, which holds its own reference
        Field jobField = BackgroundJobService.class.getDeclaredField("logStore");
        jobField.setAccessible(true);
        jobField.set(dial.getProxy().getBackgroundJobService(), store);
        return rows;
    }

    /**
     * Waits for the rows of a request: they are saved right after the response ends, the router row as the pass that
     * asked the router finishes, so they may trail the client by a moment.
     */
    @SneakyThrows
    private static List<AnalyticsLogContext> awaitRows(List<AnalyticsLogContext> rows, int count) {
        for (int i = 0; i < 250 && rows.size() < count; i++) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        assertEquals(count, rows.size(), () -> rows.stream().map(AnalyticsLogContext::getDeploymentName).toList().toString());
        return rows;
    }

    private static AnalyticsLogContext routerRow(List<AnalyticsLogContext> rows) {
        return rows.stream().filter(row -> row.getDecision() != null).collect(onlyElement());
    }

    private static AnalyticsLogContext row(List<AnalyticsLogContext> rows, String deployment) {
        return rows.stream().filter(row -> deployment.equals(row.getDeploymentName()) && row.getDecision() == null).collect(onlyElement());
    }
}
