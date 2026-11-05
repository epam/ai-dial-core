package com.epam.aidial.core.server;

import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.RouterDecision;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.service.BackgroundJobService;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final String MESSAGE = "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String EMBEDDINGS = "{\"object\":\"list\",\"data\":[],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}";
    private static final String RESPONSE = "{\"id\":\"resp_1\",\"object\":\"response\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String QUEUED = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"queued\",\"output\":[]}";
    private static final String COMPLETED = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\",\"output\":[],"
            + "\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    private static final String DEPLOYMENT_ID = "X-DIAL-DEPLOYMENT-ID";

    private final List<RecordedRequest> received = Collections.synchronizedList(new ArrayList<>());
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
        assertEquals("p1", ProxyUtil.MAPPER.readTree(decide.getHeader("X-DIAL-USER-INFO")).get("project").asText());
        assertNull(decide.getHeader("api-key"));
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
    void bodyOnRequest() {
        verdict.set("{\"candidates\": [\"m1\"]}");
        String body = "{\"model\":\"r1\",\"messages\":[]}";
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/deployments/r3/chat/completions", null, body,
                    "content-type", "application/json"), 200);
        }
        RecordedRequest decide = single(this::isDecide);
        assertEquals("/decide-body", decide.getPath());
        assertEquals(body, decide.getBody().readUtf8());
        assertEquals("application/json", decide.getHeader("content-type"));
        single(request -> request.getPath().equals("/openai/deployments/m1/chat/completions"));
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
        verdict.set("{\"deployment\": \"gpt-4\", \"ttl\": 60}");
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200, COMPLETION);
        }
        single(request -> request.getPath().equals("/openai/deployments/gpt-4-real/chat/completions"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void schemaMismatch() {
        try (TestWebServer server = server()) {
            for (String answer : List.of("{\"candidates\": []}", "{\"model\": \"gpt-4\"}", "not json")) {
                verdict.set(answer);
                verify(sendChat("r1"), 502);
            }
        }
        assertEquals(3, received.size());
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

            // the job's row is written when the poll, through Core, finds the origin's answer terminal
            for (int i = 0; i < 100 && rows.isEmpty(); i++) {
                Thread.sleep(50);
            }
        }
        AnalyticsLogContext row = singleRow(rows);
        assertEquals("gpt-4", row.getDeploymentName());
        assertEquals(List.of("r3", "gpt-4"), row.getExecutionPath());
        assertEquals("r3", row.getParentDeployment());
        assertEquals("r3", row.getDecision().router());
        assertEquals("openaiResponses", row.getDecision().api());
        assertEquals("gpt-4", row.getDecision().deployment());
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
    void decisionForOneInterfaceProxyForAnother() {
        try (TestWebServer server = server()) {
            verify(send(HttpMethod.POST, "/openai/v1/responses", null,
                    "{\"model\":\"r1\",\"store\":false,\"input\":\"hi\"}", "content-type", "application/json"), 200);
        }
        RecordedRequest router = single(request -> true);
        assertEquals("/openai/v1/responses", router.getPath());
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
        assertEquals(502, callbackStatus.get());
        assertEquals(1, received.stream().filter(this::isDecide).count());
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

        AnalyticsLogContext row = singleRow(rows);
        assertEquals("m2", row.getDeploymentName());
        assertEquals(List.of("r1", "m2"), row.getExecutionPath());
        assertEquals("r1", row.getParentDeployment());
        RouterDecision.Row decision = row.getDecision();
        assertEquals("r1", decision.router());
        assertEquals("openaiChatCompletions", decision.api());
        assertEquals(List.of("m1", "m2"), decision.candidates());
        assertEquals("m2", decision.deployment());
        assertEquals(List.of(new RouterDecision.Attempt("m1", 502, "upstream")), decision.attempts());
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
        AnalyticsLogContext row = singleRow(rows);
        assertEquals("m-int", row.getDeploymentName());
        assertEquals(List.of("r1", "i2", "m-int"), row.getExecutionPath());
        assertEquals("r1", row.getParentDeployment());
        RouterDecision.Row decision = row.getDecision();
        assertEquals("r1", decision.router());
        assertEquals(List.of("private-model", "m-int"), decision.candidates());
        assertEquals("m-int", decision.deployment());
        assertEquals(List.of(new RouterDecision.Attempt("private-model", 403, "forbidden")), decision.attempts());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void bodyFailureAfterTheAnswerIsNotRetried() {
        verdict.set("{\"candidates\": [\"m1\", \"m2\"]}");
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/openai/deployments/m1/chat/completions", request -> {
                received.add(request);
                return TestWebServer.createResponse(200, COMPLETION, "content-type", "application/json")
                        .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY);
            });
            verify(sendChat("r1"), 502);
        }
        single(request -> request.getPath().equals("/openai/deployments/m1/chat/completions"));
        assertTrue(received.stream().noneMatch(request -> request.getPath().contains("/m2/")));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void slowRouter() {
        try (TestWebServer server = server()) {
            server.map(HttpMethod.POST, "/decide-slow", request -> TestWebServer.createResponse(200, verdict.get(), "content-type", "application/json")
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
    void oneRowForDecidedRequest() {
        List<AnalyticsLogContext> rows = captureRows();
        try (TestWebServer server = server()) {
            verify(sendChat("r1"), 200);
        }
        AnalyticsLogContext row = singleRow(rows);
        assertEquals("gpt-4", row.getDeploymentName());
        assertEquals(List.of("r1", "gpt-4"), row.getExecutionPath());
        assertEquals("r1", row.getParentDeployment());
        assertEquals(List.of(), row.getDecision().attempts());
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

    private boolean isDecide(RecordedRequest request) {
        return request.getPath().startsWith("/decide");
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
    private List<AnalyticsLogContext> captureRows() {
        List<AnalyticsLogContext> rows = Collections.synchronizedList(new ArrayList<>());
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

    @SneakyThrows
    private static AnalyticsLogContext singleRow(List<AnalyticsLogContext> rows) {
        // the row is saved right after the response ends, so it may trail the client by a moment
        for (int i = 0; i < 50 && rows.isEmpty(); i++) {
            Thread.sleep(20);
        }
        Thread.sleep(100);
        assertEquals(1, rows.size(), () -> rows.stream().map(AnalyticsLogContext::getDeploymentName).toList().toString());
        return rows.getFirst();
    }
}
