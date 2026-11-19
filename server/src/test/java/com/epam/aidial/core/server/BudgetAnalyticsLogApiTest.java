package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.SpendingLimitMode;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.server.limiter.budget.BudgetCheck;
import com.epam.aidial.core.server.limiter.budget.BudgetCounterStore;
import com.epam.aidial.core.server.limiter.budget.BudgetPayer;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The spending limits object in the analytics row of a model call (spec: analytics-log).
 */
class BudgetAnalyticsLogApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/budget-spend-caps.json";
    private static final String SHADOW_CONFIG = "dial-config/budget-spend-caps-shadow.json";
    private static final String OFF_CONFIG = "dial-config/budget-analytics-off.json";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String RESPONSES_PATH = "/openai/v1/responses";
    private static final String APP_CHAIN_PATH = "/openai/deployments/app-chain/chat/completions";
    private static final String POLICIES = "/v1/policies/platform/";
    private static final String ASSIGNMENTS = "/v1/policy_assignments/platform/";
    private static final String MANAGER = """
            [ { "source": "title", "function": "EQUAL", "targets": ["Manager"] } ]""";
    private static final String USER_A = "user@projA";
    // input 10 + cache read 2 + output 8 tokens at $0.025 each: $0.50
    private static final String MESSAGES_ANSWER = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-spend\",\"content\":[{\"type\":\"text\",\"text\":\"Who's there?\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8,\"cache_read_input_tokens\":2}}";
    private static final String RESPONSES_ANSWER = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
            + "\"model\":\"gpt-spend\",\"output\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":10,\"total_tokens\":20}}";
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"app-chain\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    @Test
    @DialConfigLocation(CONFIG)
    void testEnforceModeRowNamesTheBudget() {
        budget("projA/a1", 10);
        try (TestWebServer server = upstreams()) {
            verify(postMessages(USER_A), 200);

            assertPaid(modelRow("claude-spend"), SpendingLimitMode.ENFORCE, "projA", "projA-a1", "projA/a1");
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowModeRowNamesTheBudget() {
        budget("projA/a1", 10);
        try (TestWebServer server = upstreams()) {
            verify(postMessages(USER_A), 200);

            assertPaid(modelRow("claude-spend"), SpendingLimitMode.SHADOW, "projA", "projA-a1", "projA/a1");
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testFallThroughRowNamesTheSecondBudget() {
        budget("projA/a1", 10);
        budget("projA/a2", 20);
        spend("projA/a1", "claude-spend", "10");
        try (TestWebServer server = upstreams()) {
            verify(postMessages(USER_A), 200);

            assertPaid(modelRow("claude-spend"), SpendingLimitMode.ENFORCE, "projA", "projA-a2", "projA/a2");
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTokenWithoutProjectNamesTheDefaultCostObject() {
        budget("ORG/o1", 10);
        try (TestWebServer server = upstreams()) {
            verify(postMessages("user"), 200);

            AnalyticsLogContext row = modelRow("claude-spend");
            assertNull(row.getProject());
            assertPaid(row, SpendingLimitMode.ENFORCE, "ORG", "ORG-o1", "ORG/o1");
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSelfApplyingPolicyHasNoAssignment() {
        verify(send(HttpMethod.PUT, POLICIES + "ORG/personal", null, """
                { "priority": 10, "kind": "budget", "quantity": "cost", "object": "*", "per": ["subject"],
                  "cost": { "day": 10 }, "subject": %s, "costObject": "ORG" }
                """.formatted(MANAGER), "authorization", "admin"), 200);
        try (TestWebServer server = upstreams()) {
            verify(postMessages("user"), 200);

            assertPaid(modelRow("claude-spend"), SpendingLimitMode.ENFORCE, "ORG", "ORG/personal", null);
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowExhaustedRowSaysSo() {
        budget("projA/a1", 10);
        spend("projA/a1", "claude-spend", "10");
        try (TestWebServer server = upstreams()) {
            verify(postMessages(USER_A), 200);

            assertCheck(modelRow("claude-spend"), SpendingLimitMode.SHADOW, "projA", BudgetCheck.Outcome.EXHAUSTED);
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowNoBudgetRowSaysSo() {
        try (TestWebServer server = upstreams()) {
            verify(postMessages("user"), 200);

            AnalyticsLogContext row = modelRow("claude-spend");
            assertNull(row.getProject());
            assertCheck(row, SpendingLimitMode.SHADOW, "ORG", BudgetCheck.Outcome.NO_BUDGET);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testEnforceRefusedRequestWritesNoRow() {
        budget("projA/a1", 10);
        spend("projA/a1", "claude-spend", "10");
        try (TestWebServer server = upstreams()) {
            verify(postMessages(USER_A), 429);
            // a later call of an API key writes its row after the refused one has been answered
            verify(send(HttpMethod.POST, MESSAGES_PATH, null, messagesBody(),
                    "content-type", "application/json", "api-key", "proxyKey1", "anthropic-version", "2023-06-01"), 200);

            assertNull(modelRow("claude-spend").getBudgetCheck());
            assertEquals(1, List.copyOf(analyticsRows).stream().filter(row -> "claude-spend".equals(row.getDeploymentName())).count());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApiKeyRowHasNoBudget() {
        budget("projA/a1", 10);
        try (TestWebServer server = upstreams()) {
            verify(send(HttpMethod.POST, MESSAGES_PATH, null, messagesBody(),
                    "content-type", "application/json", "api-key", "proxyKey1", "anthropic-version", "2023-06-01"), 200);

            assertNull(modelRow("claude-spend").getBudgetCheck());
        }
    }

    @Test
    @DialConfigLocation(OFF_CONFIG)
    void testSpendingLimitsOffRowHasTheModeOnly() {
        budget("projA/a1", 10);
        try (TestWebServer server = upstreams()) {
            verify(postMessages(USER_A), 200);

            assertCheck(modelRow("claude-spend"), SpendingLimitMode.OFF, null, null);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCallNamesTheInitiatorBudgetOnTheModelRowOnly() {
        budget("projA/a1", 10);
        AtomicReference<Response> inner = new AtomicReference<>();
        try (TestWebServer server = upstreams()) {
            server.map(HttpMethod.POST, APP_CHAIN_PATH, request -> {
                inner.set(send(HttpMethod.POST, RESPONSES_PATH, null,
                        "{\"model\":\"gpt-spend\",\"stream\":false,\"store\":false,\"input\":\"hi\"}",
                        "content-type", "application/json", "api-key", request.getHeader("Api-Key")));
                return TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json");
            });

            verify(send(HttpMethod.POST, APP_CHAIN_PATH, null, "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json", "authorization", USER_A), 200);
            verify(inner.get(), 200);

            assertPaid(modelRow("gpt-spend"), SpendingLimitMode.ENFORCE, "projA", "projA-a1", "projA/a1");
            assertNull(modelRow("app-chain").getBudgetCheck());
        }
    }

    private static void assertPaid(AnalyticsLogContext row, SpendingLimitMode mode, String costObject, String policy, String assignment) {
        BudgetCheck check = assertCheck(row, mode, costObject, BudgetCheck.Outcome.PAID);
        BudgetPayer payer = check.payer();
        assertNotNull(payer);
        assertEquals(policy, payer.instance().policyPath());
        assertEquals(assignment, payer.instance().assignmentPath());
    }

    private static BudgetCheck assertCheck(AnalyticsLogContext row, SpendingLimitMode mode, String costObject, BudgetCheck.Outcome outcome) {
        BudgetCheck check = row.getBudgetCheck();
        assertNotNull(check);
        assertEquals(mode, check.mode());
        assertEquals(costObject, check.costObject());
        assertEquals(outcome, check.outcome());
        if (outcome != BudgetCheck.Outcome.PAID) {
            assertNull(check.payer());
        }
        return check;
    }

    /**
     * The row of the deployment, once Core has written it.
     */
    private AnalyticsLogContext modelRow(String deployment) {
        awaitCondition(() -> findRow(deployment) != null);
        return findRow(deployment);
    }

    private AnalyticsLogContext findRow(String deployment) {
        return List.copyOf(analyticsRows).stream()
                .filter(row -> deployment.equals(row.getDeploymentName()))
                .findFirst()
                .orElse(null);
    }

    private static TestWebServer upstreams() {
        TestWebServer server = new TestWebServer(4848);
        server.map(HttpMethod.POST, MESSAGES_PATH,
                request -> TestWebServer.createResponse(200, MESSAGES_ANSWER, "Content-Type", "application/json"));
        server.map(HttpMethod.POST, RESPONSES_PATH,
                request -> TestWebServer.createResponse(200, RESPONSES_ANSWER, "Content-Type", "application/json"));
        return server;
    }

    /**
     * A policy named after the assignment, with room for $10 a day, assigned to the Manager title.
     */
    private void budget(String instanceId, int priority) {
        String name = instanceId.replace('/', '-');
        verify(send(HttpMethod.PUT, POLICIES + name, null, """
                { "priority": %d, "kind": "budget", "quantity": "cost", "object": "*", "per": ["subject"],
                  "cost": { "day": 10 } }
                """.formatted(priority), "authorization", "admin"), 200);
        ObjectNode assignment = ProxyUtil.MAPPER.createObjectNode();
        assignment.put("policy", name);
        assignment.put("costObject", instanceId.substring(0, instanceId.indexOf('/')));
        assignment.set("subject", ProxyUtil.convertToObject(MANAGER, JsonNode.class));
        verify(send(HttpMethod.PUT, ASSIGNMENTS + instanceId, null, assignment.toString(), "authorization", "admin"), 200);
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
                .charge(costObject, instance, "user", model, new BigDecimal(dollars),
                        System.currentTimeMillis(), config.getRateLimitSchedule())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private Response postMessages(String authorization) {
        return send(HttpMethod.POST, MESSAGES_PATH, null, messagesBody(),
                "content-type", "application/json", "authorization", authorization, "anthropic-version", "2023-06-01");
    }

    private static String messagesBody() {
        return "{\"model\":\"claude-spend\",\"max_tokens\":100,\"stream\":false,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}";
    }
}
