package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.server.limiter.budget.BudgetCounterStore;
import com.epam.aidial.core.server.limiter.budget.BudgetPayer;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Which budgets cover a model call along its call chain, and packages in a policy's object (specs: budget-coverage,
 * budget-packages). The router {@code r1} decides {@code claude-opus-vertex}, an alias of the model {@code m}; the tier
 * alias {@code strong} targets {@code r1}.
 */
class BudgetChainCoverageApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/budget-chain-coverage.json";
    private static final String USER_A = "user@projA";
    private static final String MANAGER = """
            [ { "source": "title", "function": "EQUAL", "targets": ["Manager"] } ]""";
    // prompt 10 + completion 10 tokens at $0.025 each: $0.50
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20}}";
    private static final String MESSAGES_ANSWER = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"m-tr\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"hi\"}],\"stop_reason\":\"end_turn\","
            + "\"usage\":{\"input_tokens\":10,\"output_tokens\":10}}";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @Test
    @DialConfigLocation(CONFIG)
    void testPackageOfTierAliasesPaysForRoutedCall() {
        engineering("[\"weak\", \"medium\", \"strong\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        try (TestWebServer server = upstreams()) {
            Response response = chat("strong");

            assertEquals(200, response.status(), response.body());
            assertEquals("projA/eng", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testModelCalledDirectlyIsNotCovered() {
        engineering("[\"weak\", \"medium\", \"strong\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        try (TestWebServer server = upstreams()) {
            assertNoBudget(chat("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTierOutsideThePackageIsNotCovered() {
        engineering("[\"weak\", \"medium\", \"strong\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        try (TestWebServer server = upstreams()) {
            assertNoBudget(chat("hi-end"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCallingTierAliasIsCovered() {
        engineering("[\"weak\", \"medium\", \"strong\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        AtomicReference<Response> callback = new AtomicReference<>();
        try (TestWebServer server = upstreams()) {
            server.map(HttpMethod.POST, "/openai/deployments/codemie/chat/completions",
                    request -> relay(callback, "/openai/deployments/strong/chat/completions", request.getHeader("api-key")));

            Response response = chat("codemie");

            assertEquals(200, response.status(), response.body());
            assertEquals(200, callback.get().status(), callback.get().body());
            assertEquals("projA/eng", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testInterceptorInFrontOfTheModelBreaksTheChain() {
        engineering("[\"strong-icpt\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        AtomicReference<Response> callback = new AtomicReference<>();
        try (TestWebServer server = upstreams()) {
            server.map(HttpMethod.POST, "/icpt/chat/completions",
                    request -> relay(callback, "/openai/deployments/interceptor/chat/completions", request.getHeader("api-key")));

            chat("strong-icpt");

            assertNoBudget(callback.get());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBudgetForEveryDeploymentCoversDirectAndRoutedCalls() {
        budget("projA/api", 10, "\"*\"", "10");
        try (TestWebServer server = upstreams()) {
            assertEquals(200, chat("m").status());
            assertEquals(200, chat("strong").status());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testAliasBudgetPaysBeforeModelBudget() {
        budget("projA/alias", 20, "[\"strong\"]", "10");
        budget("projA/model", 10, "[\"m\"]", "10");
        try (TestWebServer server = upstreams()) {
            assertEquals(200, chat("strong").status());
            assertEquals("projA/alias", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testEarliestBudgetExhaustedRefusesWithoutFallingThrough() {
        budget("projA/alias", 20, "[\"strong\"]", "1");
        budget("projA/model", 10, "[\"m\"]", "10");
        spend("projA/alias", "m", "1");
        try (TestWebServer server = upstreams()) {
            Response response = chat("strong");

            assertEquals(429, response.status(), response.body());
            assertEquals("budget_exhausted", ProxyUtil.MAPPER.readTree(response.body()).get("error").get("code").asText());
            assertAmount("0", budgetOf("model-policy-projA-model").get("day").get("used"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDirectCallIsPaidByModelBudget() {
        budget("projA/alias", 20, "[\"strong\"]", "10");
        budget("projA/model", 10, "[\"m\"]", "10");
        try (TestWebServer server = upstreams()) {
            assertEquals(200, chat("m").status());
            assertEquals("projA/model", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRouterInProxyModeIsOnTheChain() {
        engineering("[\"strong-proxy\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        try (TestWebServer server = upstreams()) {
            Response response = chat("strong-proxy");

            assertEquals(200, response.status(), response.body());
            assertEquals("projA/eng", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedCallThroughTierAliasIsCovered() {
        engineering("[\"weak\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        try (TestWebServer server = upstreams()) {
            Response response = send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"weak\",\"max_tokens\":100,\"stream\":false,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json", "authorization", USER_A, "anthropic-version", "2023-06-01");

            assertEquals(200, response.status(), response.body());
            assertEquals("projA/eng", payerOf("m-tr"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPackageEditRescopesItsPoliciesWithoutTouchingCounters() {
        engineering("[\"claude-opus-vertex\"]");
        budget("projA/eng", 10, "\"@engineering\"", "10");
        spend("projA/eng", "m", "3");
        try (TestWebServer server = upstreams()) {
            assertNoBudget(chat("hi-end"));

            engineering("[\"claude-opus-vertex\", \"hi-end\"]");
            assertAmount("3", budgetOf("model-policy-projA-eng").get("month").get("used"));

            assertEquals(200, chat("hi-end").status());
            assertEquals("projA/eng", payerOf("m2"));

            engineering("[\"claude-opus-vertex\"]");
            assertNoBudget(chat("hi-end"));
        }
    }

    private Response chat(String deployment) {
        return send(HttpMethod.POST, "/openai/deployments/" + deployment + "/chat/completions", null, CHAT_BODY,
                "content-type", "application/json", "authorization", USER_A);
    }

    /**
     * Upstreams of the fixture: the models answer, {@code r1} decides {@code claude-opus-vertex}, the proxy router
     * {@code r2} calls {@code m} back, and the translator of {@code m-tr} calls {@code m-tr} back.
     */
    private TestWebServer upstreams() {
        TestWebServer server = new TestWebServer(4848, request -> {
            String path = Objects.requireNonNull(request.getPath());
            if (path.startsWith("/r2")) {
                return relay(new AtomicReference<>(), "/openai/deployments/m/chat/completions", request.getHeader("api-key"));
            }
            if (path.startsWith("/tr")) {
                Response answer = send(HttpMethod.POST, "/openai/deployments/m-tr/chat/completions", null, CHAT_BODY,
                        "content-type", "application/json", "api-key", request.getHeader("api-key"));
                return answer.status() == 200
                        ? TestWebServer.createResponse(200, MESSAGES_ANSWER, "Content-Type", "application/json")
                        : TestWebServer.createResponse(answer.status(), answer.body(), "Content-Type", "application/json");
            }
            if (path.startsWith("/m-tr")) {
                return TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json");
            }
            return TestWebServer.createResponse(404, "unexpected " + path);
        });
        for (String model : List.of("m", "m2", "m-icpt")) {
            server.map(HttpMethod.POST, "/openai/deployments/" + model + "/chat/completions", 200, CHAT_ANSWER, "Content-Type", "application/json");
        }
        server.map(HttpMethod.POST, "/decide", 200, "{\"candidates\": [\"claude-opus-vertex\"]}", "Content-Type", "application/json");
        return server;
    }

    private MockResponse relay(AtomicReference<Response> callback, String path, String apiKey) {
        Response answer = send(HttpMethod.POST, path, null, CHAT_BODY, "content-type", "application/json", "api-key", apiKey);
        callback.set(answer);
        return TestWebServer.createResponse(answer.status(), answer.body(), "Content-Type", "application/json");
    }

    private void engineering(String deployments) {
        verify(send(HttpMethod.PUT, "/v1/packages/platform/engineering", null, "{ \"deployments\": " + deployments + " }",
                "authorization", "admin"), 200);
    }

    /**
     * One policy per assignment, {@code model-policy-<costObject>-<id>}, with a day and a month amount.
     */
    private void budget(String instanceId, int priority, String object, String amount) {
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        String policy = "model-policy-" + instanceId.replace('/', '-');
        verify(send(HttpMethod.PUT, "/v1/policies/platform/" + policy, null, """
                { "priority": %d, "kind": "budget", "quantity": "cost", "object": %s, "per": ["subject"],
                  "cost": { "day": %s, "month": %s } }
                """.formatted(priority, object, amount, amount), "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, "/v1/policy_assignments/platform/" + instanceId, null, """
                { "policy": "%s", "costObject": "%s", "subject": %s }
                """.formatted(policy, costObject, MANAGER), "authorization", "admin"), 200);
    }

    @SneakyThrows
    private void spend(String instanceId, String model, String dollars) {
        Config config = dial.getProxy().getConfigStore().get();
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        BudgetInstance instance = config.getCostObjectIndex().getInstances(costObject).stream()
                .filter(candidate -> candidate.id().equals(instanceId))
                .findFirst()
                .orElseThrow();
        new BudgetCounterStore(dial.getProxy().getTaskExecutor(), dial.getProxy().getResourceService())
                .charge(costObject, instance, "user", model, new BigDecimal(dollars), System.currentTimeMillis(), config.getRateLimitSchedule())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * The budget instance that paid the last call to {@code model}, from its analytics row.
     */
    private String payerOf(String model) {
        awaitCondition(() -> findPayer(model) != null);
        return findPayer(model).instance().id();
    }

    private BudgetPayer findPayer(String model) {
        List<AnalyticsLogContext> rows = List.copyOf(analyticsRows);
        for (int i = rows.size() - 1; i >= 0; i--) {
            AnalyticsLogContext row = rows.get(i);
            if (model.equals(row.getDeploymentName()) && row.getBudgetCheck() != null && row.getBudgetCheck().payer() != null) {
                return row.getBudgetCheck().payer();
            }
        }
        return null;
    }

    @SneakyThrows
    private JsonNode budgetOf(String policy) {
        Response response = send(HttpMethod.GET, "/v1/user/limits", null, null, "authorization", USER_A);
        assertEquals(200, response.status(), response.body());
        for (JsonNode budget : ProxyUtil.MAPPER.readTree(response.body()).get("budgets")) {
            if (policy.equals(budget.get("policy").asText())) {
                return budget;
            }
        }
        throw new AssertionError("No budget of policy " + policy + " in " + response.body());
    }

    @SneakyThrows
    private static void assertNoBudget(Response response) {
        assertNotNull(response);
        assertEquals(403, response.status(), response.body());
        assertEquals("no_budget", ProxyUtil.MAPPER.readTree(response.body()).get("error").get("code").asText());
    }

    private static void assertAmount(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())), actual.toString());
    }
}
