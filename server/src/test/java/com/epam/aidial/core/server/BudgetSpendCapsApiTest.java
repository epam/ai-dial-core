package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.server.limiter.CalendarPeriod;
import com.epam.aidial.core.server.limiter.CalendarWindowCalculator;
import com.epam.aidial.core.server.limiter.CostRateLimit;
import com.epam.aidial.core.server.limiter.budget.BudgetCounterStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Budget-backed spend caps on the Messages, Responses and Anthropic usage routes (spec: spend-limit-reporting), and
 * the budget charge of those routes (spec: budget-counters).
 */
class BudgetSpendCapsApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/budget-spend-caps.json";
    private static final String SHADOW_CONFIG = "dial-config/budget-spend-caps-shadow.json";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String RESPONSES_PATH = "/openai/v1/responses";
    private static final String USAGE_PATH = "/anthropic/api/oauth/usage";
    private static final String USER_USAGE_PATH = "/v1/user/usage";
    private static final String APP_CHAIN_PATH = "/openai/deployments/app-chain/chat/completions";
    private static final String POLICIES = "/v1/policies/platform/";
    private static final String ASSIGNMENTS = "/v1/policy_assignments/platform/";
    private static final String MANAGER = """
            [ { "source": "title", "function": "EQUAL", "targets": ["Manager"] } ]""";
    private static final String USER_A = "user@projA";
    private static final String UNIFIED = "anthropic-ratelimit-unified-";
    private static final String OVERAGE = UNIFIED + "overage-";
    private static final String PRIMARY = "x-codex-primary-";
    private static final String SECONDARY = "x-codex-secondary-";
    private static final String TWO_POW_53 = "9007199254740992";
    // input 10 + cache read 2 + output 8 tokens at $0.025 each: $0.50
    private static final String MESSAGES_ANSWER = "{\"id\":\"msg_01\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"model\":\"claude-spend\",\"content\":[{\"type\":\"text\",\"text\":\"Who's there?\"}],"
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":8,\"cache_read_input_tokens\":2}}";
    // input 10 + output 10 tokens at $0.025 each: $0.50
    private static final String RESPONSES_ANSWER = "{\"id\":\"resp_1\",\"object\":\"response\",\"status\":\"completed\","
            + "\"model\":\"gpt-spend\",\"output\":[],\"usage\":{\"input_tokens\":10,\"output_tokens\":10,\"total_tokens\":20}}";
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"app-chain\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}";

    // --- fixtures -----------------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testFixturesLoad() {
        assertFixtureAnswers();
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowFixturesLoad() {
        assertFixtureAnswers();
    }

    // --- Budgets decide the caps in enforce mode ------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testProjectBudgetReportedOnMessages() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "claude-spend", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("allowed_warning", header(response, UNIFIED + "status"));
            assertEquals("0.82", header(response, OVERAGE + "utilization"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testProjectBudgetReportedOnResponses() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("82", header(response, PRIMARY + "used-percent"));
            assertEquals("1440", header(response, PRIMARY + "window-minutes"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testRoleCostLimitReachedBudgetHasRoom() {
        roleSpend("Users/user/", "10");
        budget("projA/a1", 10, "10", null);
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals(1, upstreamCalls.get());
            assertEquals("0.00", header(response, OVERAGE + "utilization"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApiKeyCallerKeepsRoleCaps() {
        roleSpend("Keys/spend/", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = send(HttpMethod.POST, RESPONSES_PATH, null, responsesBody("gpt-spend"),
                    "content-type", "application/json", "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
            assertEquals("82", header(response, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCallKeepsRoleCaps() {
        roleSpend("Users/user/", "3");
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "claude-spend", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = postMessages("app-claude", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("0.30", header(response, OVERAGE + "utilization"));
        }

        JsonNode usage = usage(USER_A);
        assertActiveRow(usage, "daily", 82);
    }

    // --- Only a single budget is reported ---------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testTwoBudgetsAreNotReported() {
        budget("projA/a1", 10, "10", null);
        budget("projA/a2", 20, "10", null);
        try (TestWebServer server = upstreams(null)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, UNIFIED), response.headers().toString());
        }

        assertEquals("{\"limits\":[]}", getUsage(USER_A).body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSecondBudgetForAnotherModelIsCounted() {
        budget("projA/a1", 20, "10", null);
        budgetFor("projA/a2", 10, "10", null, List.of("gpt-other"));
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBudgetOfAnotherCostObject() {
        budget("projA/a1", 10, "10", null);
        budget("ORG/o1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "8.2");
        spend("ORG/o1", "gpt-spend", "1");

        assertActiveRow(usage(USER_A), "daily", 82);
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testExpiredBudgetIsNotCounted() {
        budget("projA/a1", 10, "10", null);
        Instant expiresAt = Instant.now().plusSeconds(2);
        putBudget("projA/a2", 20, "10", null, null, expiresAt.toString());
        spend("projA/a1", "gpt-spend", "8.2");
        while (!Instant.now().isAfter(expiresAt)) {
            Thread.sleep(200);
        }

        assertActiveRow(usage(USER_A), "daily", 82);
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNoBudgetGivesEmptyUsage() {
        Response response = getUsage(USER_A);

        assertEquals(200, response.status(), response.body());
        assertEquals("{\"limits\":[]}", response.body());
    }

    // --- Budget windows as spend caps --------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testDayAndMonthAmounts() {
        budget("projA/a1", 10, "10", "20.50");
        spend("projA/a1", "gpt-spend", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("82", header(response, PRIMARY + "used-percent"));
            assertEquals("1440", header(response, PRIMARY + "window-minutes"));
            assertEquals("40", header(response, SECONDARY + "used-percent"));
            assertEquals("43200", header(response, SECONDARY + "window-minutes"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testMonthAmountOnly() {
        budget("projA/a1", 10, null, "100");
        spend("projA/a1", "gpt-spend", "30");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("30", header(response, PRIMARY + "used-percent"));
            assertEquals("43200", header(response, PRIMARY + "window-minutes"));
            assertFalse(hasHeaderWithPrefix(response, SECONDARY), response.headers().toString());
        }

        JsonNode limits = usage(USER_A).get("limits");
        assertEquals(1, limits.size(), limits.toString());
        assertEquals("monthly", limits.get(0).get("group").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSpendOfEveryModelOfTheBudget() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "claude-spend", "3");
        spend("projA/a1", "claude-other", "5");
        try (TestWebServer server = upstreams(null)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("0.80", header(response, OVERAGE + "utilization"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testAmountTooLargeToReport() {
        budget("projA/a1", 10, TWO_POW_53, null);
        try (TestWebServer server = upstreams(null)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, UNIFIED), response.headers().toString());
        }

        assertEquals("{\"limits\":[]}", getUsage(USER_A).body());
    }

    // --- Cap reported from the budget ---------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testUsageEndpointAgreesWithHeaders() {
        budget("projA/a1", 10, "10", "20.50");
        spend("projA/a1", "claude-spend", "8.2");
        // read first: the headers describe the spend before the call, and the call is charged once it answers
        JsonNode usage = usage(USER_A);
        Response messages;
        try (TestWebServer server = upstreams(null)) {
            messages = postMessages("claude-spend", USER_A);
        }
        assertEquals(200, messages.status(), messages.body());
        assertEquals("0.82", header(messages, OVERAGE + "utilization"));

        JsonNode active = assertActiveRow(usage, "daily", 82);
        assertRow(usage, "monthly", 40, false);
        assertEquals(82, usage.get("extra_usage").get("utilization").asLong());
        assertEquals(header(messages, OVERAGE + "reset"),
                String.valueOf(Instant.parse(active.get("resets_at").asText()).getEpochSecond()));
    }

    // --- Exhausted budgets rejected in the client's format -------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testOneBudgetDayExhaustedOnMessages() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "claude-spend", "10");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            assertEquals("billing_error", parse(response.body()).get("error").get("type").asText());
            assertEquals("daily", header(response, OVERAGE + "period"));
            assertRetryAfterWithin(response, expectedReset(CalendarPeriod.DAY));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testOneBudgetDayExhaustedOnResponses() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "10");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            JsonNode error = parse(response.body()).get("error");
            assertEquals("usage_limit_reached", error.get("type").asText());
            assertEquals(1440, error.get("limit_window_minutes").asInt());
            assertEquals("You've exceeded your daily cost limit", error.get("display_message").asText());
            assertEquals("100", header(response, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testOneBudgetMonthExhausted() {
        budget("projA/a1", 10, null, "20");
        spend("projA/a1", "gpt-spend", "20");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(429, response.status(), response.body());
            JsonNode error = parse(response.body()).get("error");
            assertEquals("usage_limit_reached", error.get("type").asText());
            assertEquals(43200, error.get("limit_window_minutes").asInt());
            assertEquals("You've exceeded your monthly cost limit", error.get("display_message").asText());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testSeveralBudgetsAllExhausted() {
        budget("projA/a1", 10, null, "20");
        budget("projA/a2", 20, "10", null);
        spend("projA/a1", "gpt-spend", "20");
        spend("projA/a2", "gpt-spend", "10");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(429, response.status(), response.body());
            JsonNode error = parse(response.body()).get("error");
            assertEquals("usage_limit_reached", error.get("type").asText());
            long dayReset = expectedReset(CalendarPeriod.DAY);
            assertEquals(dayReset, error.get("resets_at").asLong());
            // on the last day of a month both reset at once, and the tie keeps the first budget, whose window is the month
            int expectedWindow = dayReset == expectedReset(CalendarPeriod.MONTH) ? 43200 : 1440;
            assertEquals(expectedWindow, error.get("limit_window_minutes").asInt());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testOneOfSeveralBudgetsHasRoom() {
        budget("projA/a1", 10, "10", null);
        budget("projA/a2", 20, "10", null);
        spend("projA/a1", "gpt-spend", "10");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals(1, upstreamCalls.get());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testExhaustedBudgetWinsOverTokenLimit() {
        budget("projA/a1", 10, "10", null);
        try (TestWebServer server = upstreams(null)) {
            // claude-burst allows 10 tokens a minute; the first request passes and records 20
            Response first = postMessages("claude-burst", USER_A);
            assertEquals(200, first.status(), first.body());
            Thread.sleep(1000);
            spend("projA/a1", "claude-burst", "10");

            Response second = postMessages("claude-burst", USER_A);

            assertEquals(429, second.status(), second.body());
            assertEquals("billing_error", parse(second.body()).get("error").get("type").asText());
        }
    }

    // --- No budget for the model ----------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testBudgetForAnotherModelOnly() {
        budgetFor("projA/a1", 10, "10", null, List.of("claude-other"));
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            // the DIAL error body, not the Anthropic shape with its top-level "type"
            JsonNode body = parse(response.body());
            assertNull(body.get("type"), response.body());
            JsonNode error = body.get("error");
            assertEquals("429", error.get("code").asText());
            assertEquals("No budget covers claude-spend for cost object projA", error.get("message").asText());
            assertEquals("You have no budget for this model", error.get("display_message").asText());
            assertFalse(hasHeaderWithPrefix(response, UNIFIED), response.headers().toString());
            assertNull(header(response, "retry-after"));
        }
    }

    // --- Unreadable budget counter -----------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testCounterUnreadableOnMessages() {
        budget("projA/a1", 10, "10", null);
        corrupt("projA/a1", "claude-spend");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            assertEquals("fetch_error", header(response, OVERAGE + "disabled-reason"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testCounterUnreadableOnResponses() {
        budget("projA/a1", 10, "10", null);
        corrupt("projA/a1", "gpt-spend");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(429, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            assertEquals("{\"error\":{\"code\":\"429\",\"message\":\"DIAL could not read your spend limit; request rejected\"}}",
                    response.body());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testTokenLimitDecidesOverUnreadableCounter() {
        budget("projA/a1", 10, "10", null);
        assertTokenLimitDecidesOverUnreadableCounter();
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBudgetTooLargeToCapCounterUnreadable() {
        budget("projA/a1", 10, TWO_POW_53, null);
        corrupt("projA/a1", "claude-spend");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(500, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            assertEquals("true", header(response, "x-should-retry"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testTokenLimitDecidesForHugeBudget() {
        budget("projA/a1", 10, TWO_POW_53, null);
        assertTokenLimitDecidesOverUnreadableCounter();
    }

    // --- Unreadable budget counter on the usage endpoint -------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testUsageEndpointCounterUnreadable() {
        budget("projA/a1", 10, "10", null);
        corrupt("projA/a1", "claude-spend");

        Response response = getUsage(USER_A);

        assertEquals(500, response.status(), response.body());
        assertEquals("true", header(response, "x-should-retry"));
        assertEquals("{\"type\":\"error\",\"error\":{\"type\":\"api_error\",\"message\":\"Failed to get spend limits\"}}", response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUsageEndpointBudgetTooLargeCounterUnreadable() {
        budget("projA/a1", 10, TWO_POW_53, null);
        corrupt("projA/a1", "claude-spend");

        Response response = getUsage(USER_A);

        assertEquals(200, response.status(), response.body());
        assertEquals("{\"limits\":[]}", response.body());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTokenWithoutProjectUsesDefaultCostObject() {
        budget("ORG/o1", 10, null, "20.50");
        spend("ORG/o1", "gpt-spend", "8.2");

        JsonNode limits = usage("user").get("limits");

        assertEquals(1, limits.size(), limits.toString());
        assertEquals("monthly", limits.get(0).get("group").asText());
        assertEquals(40, limits.get(0).get("percent").asInt());
    }

    // --- Budget of the chain's initiator --------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCallToTheModelUsesInitiatorBudget() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "8.2");
        AtomicReference<Response> inner = new AtomicReference<>();
        try (TestWebServer server = upstreams(null)) {
            server.map(HttpMethod.POST, APP_CHAIN_PATH, request -> {
                inner.set(send(HttpMethod.POST, RESPONSES_PATH, null, responsesBody("gpt-spend"),
                        "content-type", "application/json", "api-key", request.getHeader("Api-Key")));
                return TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json");
            });

            Response outer = send(HttpMethod.POST, APP_CHAIN_PATH, null, "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json", "authorization", USER_A);

            assertEquals(200, outer.status(), outer.body());
            assertEquals(200, inner.get().status(), inner.get().body());
            assertEquals("82", header(inner.get(), PRIMARY + "used-percent"));
        }
    }

    // --- No Codex headers without a cost cap (budget cases) ------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testSeveralBudgetsSendNoCodexHeaders() {
        budget("projA/a1", 10, "10", null);
        budget("projA/a2", 20, "10", null);
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBudgetTooLargeSendsNoCodexHeaders() {
        budget("projA/a1", 10, TWO_POW_53, null);
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    // --- Translated deployments read the budget --------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedOneBudget() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-translated", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("82", header(response, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedOneBudgetExhausted() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "claude-spend", "10");
        AtomicInteger translatorCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(translatorCalls)) {
            Response response = postMessages("claude-translated", USER_A);

            assertEquals(429, response.status(), response.body());
            assertEquals(0, translatorCalls.get());
            assertEquals("billing_error", parse(response.body()).get("error").get("type").asText());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedTwoBudgets() {
        budget("projA/a1", 10, "10", null);
        budget("projA/a2", 20, "10", null);
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-translated", USER_A);

            assertEquals(200, response.status(), response.body());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedAllExhaustedIsForwarded() {
        budget("projA/a1", 10, "10", null);
        budget("projA/a2", 20, "10", null);
        spend("projA/a1", "gpt-spend", "10");
        spend("projA/a2", "gpt-spend", "10");
        AtomicInteger translatorCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(translatorCalls)) {
            Response response = postResponses("gpt-translated", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals(1, translatorCalls.get());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedNoBudgetInCostObjectIsForwarded() {
        AtomicInteger translatorCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(translatorCalls)) {
            Response response = postMessages("claude-translated", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals(1, translatorCalls.get());
            assertFalse(hasHeaderWithPrefix(response, UNIFIED), response.headers().toString());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedOneBudgetForAnotherModel() {
        budgetFor("projA/a1", 10, null, "100", List.of("claude-other"));
        spend("projA/a1", "claude-other", "40");
        AtomicInteger translatorCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(translatorCalls)) {
            Response response = postMessages("claude-translated", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals(1, translatorCalls.get());
            assertEquals("0.40", header(response, OVERAGE + "utilization"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTranslatedCounterUnreadableIsForwarded() {
        budget("projA/a1", 10, "10", null);
        corrupt("projA/a1", "gpt-spend");
        AtomicInteger translatorCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(translatorCalls)) {
            Response response = postResponses("gpt-translated", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals(1, translatorCalls.get());
            assertFalse(hasHeaderWithPrefix(response, "x-codex-"), response.headers().toString());
        }
    }

    // --- Shadow mode keeps the role caps ------------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowReportsRoleCapsOnResponses() {
        roleSpend("Users/user/", "3");
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("30", header(response, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowUsageEndpointReportsRoleCaps() {
        roleSpend("Users/user/", "3");
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "8.2");

        assertActiveRow(usage(USER_A), "daily", 30);
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowTranslatedReportsRoleCaps() {
        roleSpend("Users/user/", "3");
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "gpt-spend", "8.2");
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-translated", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals("30", header(response, PRIMARY + "used-percent"));
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowExhaustedBudgetsDoNotReject() {
        budget("projA/a1", 10, "10", null);
        spend("projA/a1", "claude-spend", "10");
        // the test settings turn metrics off, so the global registry records nothing without a registry of its own
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(200, response.status(), response.body());
            assertEquals(1, upstreamCalls.get());
            assertEquals(1, registry.counter("dial_budget_rejections_total", "reason", "budget_exhausted", "mode", "shadow").count());
        } finally {
            Metrics.removeRegistry(registry);
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testShadowCounterUnreadableFails() {
        budget("projA/a1", 10, "10", null);
        corrupt("projA/a1", "claude-spend");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = postMessages("claude-spend", USER_A);

            assertEquals(500, response.status(), response.body());
            assertEquals(0, upstreamCalls.get());
            assertEquals("true", header(response, "x-should-retry"));
        }
    }

    // --- budget-counters: Coding client routes charge the budget ----------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testEnforceChargesTheBudget() {
        budget("projA/a1", 10, "10", "100");
        try (TestWebServer server = upstreams(null)) {
            Response response = postMessages("claude-spend", USER_A);
            assertEquals(200, response.status(), response.body());
        }
        Thread.sleep(1000); // the charge is written after the response

        JsonNode budget = userUsage(USER_A).get("budgets").get(0);
        assertAmount("0.5", budget.get("day").get("used"));
        assertAmount("0.5", budget.get("month").get("used"));
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    @SneakyThrows
    void testShadowChargesBudgetAndRole() {
        budget("projA/a1", 10, "10", null);
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-spend", USER_A);
            assertEquals(200, response.status(), response.body());
        }
        Thread.sleep(1000);

        JsonNode stats = userUsage(USER_A);
        assertAmount("0.5", stats.get("budgets").get(0).get("day").get("used"));
        assertAmount("0.5", stats.get("dayCostStats").get("used"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testFallThroughChargesTheSecondBudget() {
        budget("projA/a1", 10, "10", null);
        budget("projA/a2", 20, "10", null);
        spend("projA/a1", "claude-spend", "10");
        try (TestWebServer server = upstreams(null)) {
            Response response = postMessages("claude-spend", USER_A);
            assertEquals(200, response.status(), response.body());
        }
        Thread.sleep(1000);

        ArrayNode budgets = (ArrayNode) userUsage(USER_A).get("budgets");
        assertAmount("10", budgets.get(0).get("day").get("used"));
        assertAmount("0.5", budgets.get(1).get("day").get("used"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testTranslatedOuterRequestChargesNoBudget() {
        budget("projA/a1", 10, "10", null);
        try (TestWebServer server = upstreams(null)) {
            Response response = postResponses("gpt-translated", USER_A);
            assertEquals(200, response.status(), response.body());
        }
        Thread.sleep(1000);

        assertAmount("0", userUsage(USER_A).get("budgets").get(0).get("day").get("used"));
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testUserUsageNamesTheCostObject() {
        budget("projA/a1", 10, "10", null);

        assertEquals("projA", userUsage(USER_A).get("costObject").asText());
    }

    // --- helpers ------------------------------------------------------------------------------------------------------------------

    // an API key stays on the role cost limit in every mode, so it reaches the models whatever the budgets
    private void assertFixtureAnswers() {
        try (TestWebServer server = upstreams(null)) {
            Response messages = send(HttpMethod.POST, MESSAGES_PATH, null, messagesBody("claude-spend"),
                    "content-type", "application/json", "api-key", "proxyKey1", "anthropic-version", "2023-06-01");
            Response responses = send(HttpMethod.POST, RESPONSES_PATH, null, responsesBody("gpt-spend"),
                    "content-type", "application/json", "api-key", "proxyKey1");

            assertEquals(200, messages.status(), messages.body());
            assertEquals(200, responses.status(), responses.body());
        }
    }

    @SneakyThrows
    private void assertTokenLimitDecidesOverUnreadableCounter() {
        try (TestWebServer server = upstreams(null)) {
            // claude-burst allows 10 tokens a minute; the first request passes and records 20
            Response first = postMessages("claude-burst", USER_A);
            assertEquals(200, first.status(), first.body());
            Thread.sleep(1000);
            corrupt("projA/a1", "claude-burst");

            Response second = postMessages("claude-burst", USER_A);

            assertEquals(429, second.status(), second.body());
            assertEquals("rate_limit_error", parse(second.body()).get("error").get("type").asText());
        }
    }

    /**
     * Answers Messages and Responses requests, which also serve the translated models' outer requests.
     */
    private static TestWebServer upstreams(AtomicInteger calls) {
        TestWebServer server = new TestWebServer(4848);
        server.map(HttpMethod.POST, MESSAGES_PATH, request -> {
            if (calls != null) {
                calls.incrementAndGet();
            }
            return TestWebServer.createResponse(200, MESSAGES_ANSWER, "Content-Type", "application/json");
        });
        server.map(HttpMethod.POST, RESPONSES_PATH, request -> {
            if (calls != null) {
                calls.incrementAndGet();
            }
            return TestWebServer.createResponse(200, RESPONSES_ANSWER, "Content-Type", "application/json");
        });
        return server;
    }

    private void budget(String instanceId, int priority, String day, String month) {
        putBudget(instanceId, priority, day, month, null, null);
    }

    private void budgetFor(String instanceId, int priority, String day, String month, List<String> models) {
        putBudget(instanceId, priority, day, month, models, null);
    }

    /**
     * One policy per assignment, named after it, so every budget can have its own amounts.
     */
    private void putBudget(String instanceId, int priority, String day, String month, List<String> models, String expiresAt) {
        String name = instanceId.replace('/', '-');
        ObjectNode policy = ProxyUtil.MAPPER.createObjectNode();
        policy.put("priority", priority);
        policy.put("kind", "budget");
        policy.put("quantity", "cost");
        if (models == null) {
            policy.put("object", "*");
        } else {
            models.forEach(policy.putArray("object")::add);
        }
        policy.putArray("per").add("subject");
        ObjectNode cost = policy.putObject("cost");
        if (day != null) {
            cost.put("day", new BigDecimal(day));
        }
        if (month != null) {
            cost.put("month", new BigDecimal(month));
        }
        verify(send(HttpMethod.PUT, POLICIES + name, null, policy.toString(), "authorization", "admin"), 200);

        ObjectNode assignment = ProxyUtil.MAPPER.createObjectNode();
        assignment.put("policy", name);
        assignment.put("costObject", instanceId.substring(0, instanceId.indexOf('/')));
        assignment.set("subject", parse(MANAGER));
        if (expiresAt != null) {
            assignment.put("expiresAt", expiresAt);
        }
        verify(send(HttpMethod.PUT, ASSIGNMENTS + instanceId, null, assignment.toString(), "authorization", "admin"), 200);
    }

    @SneakyThrows
    private void spend(String instanceId, String model, String dollars) {
        Config config = dial.getProxy().getConfigStore().get();
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        new BudgetCounterStore(dial.getProxy().getTaskExecutor(), dial.getProxy().getResourceService())
                .charge(costObject, instance(config, instanceId), "user", model, new BigDecimal(dollars),
                        System.currentTimeMillis(), config.getRateLimitSchedule())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    /**
     * Writes a row that is not a counter where {@code BudgetCounterStore} keeps the user's row of the model, so
     * reading the budget fails.
     */
    private void corrupt(String instanceId, String model) {
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        String instanceKey = instanceId.substring(instanceId.indexOf('/') + 1);
        String path = Stream.of(costObject, "budgets", instanceKey, "user", model, "costs")
                .map(UrlUtil::encodePathSegment)
                .collect(Collectors.joining("/"));
        ResourceDescriptor row = ResourceDescriptorFactory.fromEncoded(ResourceTypes.LIMIT, ResourceDescriptor.PLATFORM_BUCKET,
                ResourceDescriptor.PLATFORM_LOCATION, path);
        dial.getProxy().getResourceService().computeResource(row, json -> "not json");

        // the layout is copied from the store: if it drifts, fail here rather than test a readable counter
        Config config = dial.getProxy().getConfigStore().get();
        CompletableFuture<?> load = new BudgetCounterStore(dial.getProxy().getTaskExecutor(), dial.getProxy().getResourceService())
                .load(costObject, instance(config, instanceId), "user", System.currentTimeMillis(), config.getRateLimitSchedule())
                .toCompletionStage().toCompletableFuture();
        assertThrows(ExecutionException.class, () -> load.get(10, TimeUnit.SECONDS));
    }

    private void roleSpend(String bucket, String dollars) {
        ResourceDescriptor descriptor = ResourceDescriptorFactory.fromEntityPath(ResourceTypes.LIMIT, bucket, bucket, "costs");
        dial.getProxy().getResourceService().computeResource(descriptor, json -> {
            CostRateLimit limit = json == null ? new CostRateLimit() : ProxyUtil.convertToObject(json, CostRateLimit.class);
            limit.add(System.currentTimeMillis(), new RateLimitSchedule(), new BigDecimal(dollars));
            return ProxyUtil.convertToString(limit);
        });
    }

    private static BudgetInstance instance(Config config, String instanceId) {
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        return config.getCostObjectIndex().getInstances(costObject).stream()
                .filter(candidate -> candidate.id().equals(instanceId))
                .findFirst()
                .orElseThrow();
    }

    private Response postMessages(String model, String authorization) {
        return send(HttpMethod.POST, MESSAGES_PATH, null, messagesBody(model),
                "content-type", "application/json", "authorization", authorization, "anthropic-version", "2023-06-01");
    }

    private static String messagesBody(String model) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":100,\"stream\":false,"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Knock, knock\"}]}";
    }

    private Response postResponses(String model, String authorization) {
        return send(HttpMethod.POST, RESPONSES_PATH, null, responsesBody(model),
                "content-type", "application/json", "authorization", authorization);
    }

    private static String responsesBody(String model) {
        return "{\"model\":\"" + model + "\",\"stream\":false,\"store\":false,\"input\":\"hi\"}";
    }

    private Response getUsage(String authorization) {
        return send(HttpMethod.GET, USAGE_PATH, null, null, "authorization", authorization);
    }

    private JsonNode usage(String authorization) {
        Response response = getUsage(authorization);
        assertEquals(200, response.status(), response.body());
        return parse(response.body());
    }

    private JsonNode userUsage(String authorization) {
        Response response = send(HttpMethod.GET, USER_USAGE_PATH, null, null, "authorization", authorization);
        assertEquals(200, response.status(), response.body());
        return parse(response.body());
    }

    private static JsonNode assertActiveRow(JsonNode usage, String group, int percent) {
        return assertRow(usage, group, percent, true);
    }

    private static JsonNode assertRow(JsonNode usage, String group, int percent, boolean active) {
        for (JsonNode row : usage.get("limits")) {
            if (group.equals(row.get("group").asText())) {
                assertEquals(percent, row.get("percent").asInt(), row.toString());
                assertEquals(active, row.get("is_active").asBoolean(), row.toString());
                return row;
            }
        }
        throw new AssertionError("No row for group " + group + " in " + usage);
    }

    private static void assertAmount(String expected, JsonNode actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(new BigDecimal(actual.asText())), actual.toString());
    }

    private static void assertRetryAfterWithin(Response response, long resetSeconds) {
        long retryAfter = Long.parseLong(header(response, "retry-after"));
        long untilReset = resetSeconds - System.currentTimeMillis() / 1000;
        // counts down to the reset itself, not to a short rolling window
        assertTrue(retryAfter >= untilReset - 5 && retryAfter <= untilReset + 1, retryAfter + " vs " + untilReset);
    }

    private static long expectedReset(CalendarPeriod period) {
        return CalendarWindowCalculator.nextPeriodStart(period, System.currentTimeMillis(), new RateLimitSchedule()) / 1000;
    }

    private static String header(Response response, String name) {
        return response.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue())
                .findFirst()
                .orElse(null);
    }

    private static boolean hasHeaderWithPrefix(Response response, String prefix) {
        return response.headers().keySet().stream().anyMatch(name -> name.regionMatches(true, 0, prefix, 0, prefix.length()));
    }

    @SneakyThrows
    private static JsonNode parse(String body) {
        return ProxyUtil.MAPPER.readTree(body);
    }
}
