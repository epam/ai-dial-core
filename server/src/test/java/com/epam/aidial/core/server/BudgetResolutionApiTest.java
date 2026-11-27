package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.server.limiter.budget.BudgetCheck;
import com.epam.aidial.core.server.limiter.budget.BudgetCounterStore;
import com.epam.aidial.core.server.limiter.budget.CostObject;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which budget pays a JWT caller's call (spec: budget-resolution), the refusals of the ladder (spec:
 * spending-refusals) and the cost object source in the analytics row (spec: analytics-log). {@code strong} is an alias of
 * {@code m}, {@code hi-end} of {@code m2}. Callers are {@code path:<sub>@<claim>} (the provider sets
 * {@code costObjectPath}) and {@code nopath:<sub>} (it does not).
 */
class BudgetResolutionApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/budget-resolution.json";
    private static final String SHADOW_CONFIG = "dial-config/budget-resolution-shadow.json";
    private static final String MANDATORY_CONFIG = "dial-config/budget-resolution-mandatory.json";
    private static final String NO_WALK_CONFIG = "dial-config/budget-resolution-no-walk.json";
    private static final String BUDGET = "X-DIAL-BUDGET";
    private static final String COST_OBJECT = "X-DIAL-COST-OBJECT";
    private static final String EVERYONE = "[ { \"source\": \"sub\", \"function\": \"TRUE\" } ]";
    private static final String ALICE_IMF = "path:alice@IMF";
    private static final String ALICE_NO_CLAIM = "path:alice@";
    private static final String ALICE_NO_PATH = "nopath:alice";
    // prompt 10 + completion 10 tokens at $0.025 each: $0.50
    private static final String CHAT_ANSWER = "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"m\","
            + "\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":10,\"total_tokens\":20}}";
    private static final String CHAT_BODY = "{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    // what the application does with its per-request key; answers 200 without calling anything by default
    private final AtomicReference<Function<String, MockResponse>> app = new AtomicReference<>(
            apiKey -> TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json"));

    // --- Malformed budget headers refused ------------------------------------------------------------------------

    @Test
    @DialConfigLocation(MANDATORY_CONFIG)
    void testIndicationWithOneElement() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = chat("m", ALICE_IMF, BUDGET, "TKT-1");

            assertRefused(response, 400, "invalid_budget_indication", "X-DIAL-BUDGET must be <costObject>/<id>");
            assertEquals(0, upstreamCalls.get());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testCostObjectWithSlash() {
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            Response response = chat("app", ALICE_NO_PATH, BUDGET, "IMF/TKT-1", COST_OBJECT, "IMF/eng");

            assertRefused(response, 400, "invalid_cost_object", "X-DIAL-COST-OBJECT must be one cost object name");
            assertEquals(0, upstreamCalls.get());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testMalformedHeaderWhereClaimPathIsSet() {
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("m", ALICE_IMF, COST_OBJECT, "a/b"), 400, "invalid_cost_object", null);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testMalformedHeaderFromAnApiKey() {
        try (TestWebServer server = upstreams(null)) {
            Response response = send(HttpMethod.POST, "/openai/deployments/m/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json", "api-key", "proxyKey1", BUDGET, "x");

            assertEquals(200, response.status(), response.body());
        }
    }

    // --- Indicated budget's cost object must match ---------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testClaimMissingIndicationOnTheProject() {
        ticket();
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("strong", ALICE_NO_CLAIM, BUDGET, "IMF/TKT-1"), 400, "cost_object_mismatch",
                    "Budget IMF/TKT-1 is on cost object IMF, but this call is charged to ORG (default)");
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testIndicationOnTheClaimsCostObject() {
        projectDefault();
        try (TestWebServer server = upstreams(null)) {
            Response response = chat("m", ALICE_IMF, BUDGET, "IMF/default-members");

            assertEquals(200, response.status(), response.body());
            assertEquals("IMF/default-members", payerOf("m"));
        }
    }

    // --- Indication required -------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(MANDATORY_CONFIG)
    void testUnindicatedCallWithTheFlagOn() {
        personal();
        setDefault(ALICE_NO_CLAIM, "ORG/personal");
        AtomicInteger upstreamCalls = new AtomicInteger();
        try (TestWebServer server = upstreams(upstreamCalls)) {
            assertRefused(chat("app", ALICE_NO_CLAIM), 400, "budget_indication_required",
                    "Indicate the budget that pays with the X-DIAL-BUDGET header");
            assertEquals(0, upstreamCalls.get());
        }
    }

    @Test
    @DialConfigLocation(MANDATORY_CONFIG)
    void testApiKeyCallerWithTheFlagOn() {
        try (TestWebServer server = upstreams(null)) {
            Response response = send(HttpMethod.POST, "/openai/deployments/m/chat/completions", null, CHAT_BODY,
                    "content-type", "application/json", "api-key", "proxyKey1");

            assertEquals(200, response.status(), response.body());
        }
    }

    @Test
    @DialConfigLocation(MANDATORY_CONFIG)
    void testIndicationRequiredOnResponses() {
        try (TestWebServer server = upstreams(null)) {
            Response response = send(HttpMethod.POST, "/openai/v1/responses", null, "{\"model\":\"m\",\"input\":\"hi\"}",
                    "content-type", "application/json", "authorization", ALICE_IMF);

            assertRefused(response, 400, "budget_indication_required", "Indicate the budget that pays with the X-DIAL-BUDGET header");
        }
    }

    @Test
    @DialConfigLocation(MANDATORY_CONFIG)
    void testCostObjectMismatchMessageOnMessages() {
        try (TestWebServer server = upstreams(null)) {
            Response response = send(HttpMethod.POST, "/anthropic/v1/messages", null,
                    "{\"model\":\"m\",\"max_tokens\":10,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}",
                    "content-type", "application/json", "authorization", ALICE_NO_CLAIM, "anthropic-version", "2023-06-01",
                    BUDGET, "IMF/TKT-1");

            assertRefused(response, 400, "cost_object_mismatch", "Budget IMF/TKT-1 is on cost object IMF, but this call is charged to ORG (default)");
        }
    }

    // --- Effective cost object -----------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testClaimPresent() {
        projectDefault();
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("m", ALICE_IMF, COST_OBJECT, "EDU").status());

            AnalyticsLogContext row = rowOf("m");
            assertEquals("IMF", row.getCostObject());
            assertEquals(CostObject.Source.CLAIM, row.getCostObjectSource());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testClaimMissingHeaderIgnored() {
        ticket();
        personal();
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("m", ALICE_NO_CLAIM, COST_OBJECT, "IMF").status());

            assertEquals("ORG/personal", payerOf("m"));
            AnalyticsLogContext row = rowOf("m");
            assertEquals("ORG", row.getCostObject());
            assertEquals(CostObject.Source.DEFAULT, row.getCostObjectSource());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testHeaderScopesTheWalk() {
        ticket();
        personal();
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("strong", ALICE_NO_PATH, COST_OBJECT, "IMF").status());

            assertEquals("IMF/TKT-1", payerOf("m"));
            AnalyticsLogContext row = rowOf("m");
            assertEquals("IMF", row.getCostObject());
            assertEquals(CostObject.Source.HEADER, row.getCostObjectSource());
            assertEquals("IMF", row.getBudgetCheck().costObject());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testHeaderAndIndication() {
        ticket();
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("strong", ALICE_NO_PATH, COST_OBJECT, "EDU", BUDGET, "IMF/TKT-1").status());

            assertEquals("IMF/TKT-1", payerOf("m"));
            assertEquals("IMF", rowOf("m").getCostObject());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testTicketIndicatedWithoutClaimPath() {
        ticket();
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("strong", ALICE_NO_PATH, BUDGET, "IMF/TKT-1").status());

            assertEquals("IMF/TKT-1", payerOf("m"));
            assertEquals(CostObject.Source.HEADER, rowOf("m").getCostObjectSource());
        }
    }

    // --- Indicated budget is the payer ---------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testAnotherPersonsTicket() {
        ticket();
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("strong", "path:dave@IMF", BUDGET, "IMF/TKT-1"), 403, "no_budget", "Budget IMF/TKT-1 is not available to you");
            assertRefused(chat("strong", "path:dave@IMF", BUDGET, "IMF/missing"), 403, "no_budget", "Budget IMF/missing is not available to you");
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testIndicatedBudgetDoesNotCoverTheModel() {
        ticket();
        projectDefault();
        try (TestWebServer server = upstreams(null)) {
            Response response = chat("hi-end", ALICE_IMF, BUDGET, "IMF/TKT-1");

            assertRefused(response, 403, "budget_not_applicable", "Budget IMF/TKT-1 does not cover hi-end");
            assertTrue(response.headers().get("retry-after") == null);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testIndicatedBudgetUsedUp() {
        ticket();
        projectDefault();
        spend("IMF/TKT-1", "alice", "m", "10");
        try (TestWebServer server = upstreams(null)) {
            Response response = chat("strong", ALICE_IMF, BUDGET, "IMF/TKT-1");

            assertRefused(response, 429, "budget_exhausted", null);
            assertNotNull(response.headers().get("retry-after"));
        }
    }

    // --- Header cost object does not attest ----------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testHeaderWalkSkipsTrueOnlyBudget() {
        projectDefault();
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("m", ALICE_NO_PATH, COST_OBJECT, "IMF"), 403, "no_budget", null);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testIndicatedTrueOnlyBudgetWithoutClaimPath() {
        projectDefault();
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("m", "nopath:dave", BUDGET, "IMF/default-members"), 403, "no_budget", null);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testHeaderWalkReachesNamedBudget() {
        // the ticket comes second in priority order, so only the attestation rule makes it the payer
        budget("IMF/default-members", 1, "\"*\"", EVERYONE, "10");
        budget("IMF/TKT-1", 20, "\"*\"", alice(), "10");
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("m", ALICE_NO_PATH, COST_OBJECT, "IMF").status());

            assertEquals("IMF/TKT-1", payerOf("m"));
        }
    }

    // --- Default budget pays an unindicated call -----------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testDefaultPaysOverHigherPriorityBudget() {
        budget("IMF/default-members", 20, "\"*\"", EVERYONE, "10");
        budget("IMF/TKT-1", 10, "\"*\"", alice(), "10");
        setDefault(ALICE_IMF, "IMF/default-members");
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("m", ALICE_IMF).status());

            assertEquals("IMF/default-members", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDefaultDoesNotCoverTheCall() {
        ticket();
        projectDefault();
        setDefault(ALICE_IMF, "IMF/TKT-1");
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("hi-end", ALICE_IMF), 403, "default_budget_not_applicable",
                    "Your default budget IMF/TKT-1 does not cover hi-end. Indicate a budget with the X-DIAL-BUDGET header.");
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDefaultUsedUp() {
        ticket();
        projectDefault();
        setDefault(ALICE_IMF, "IMF/TKT-1");
        spend("IMF/TKT-1", "alice", "m", "10");
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("strong", ALICE_IMF), 429, "budget_exhausted", null);
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDefaultOnAnotherCostObject() {
        projectDefault();
        budget("EDU/e1", 1, "\"*\"", alice(), "10");
        setDefault(ALICE_IMF, "EDU/e1");
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("m", ALICE_IMF).status());

            assertEquals("IMF/default-members", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testDefaultWithdrawn() {
        projectDefault();
        budget("IMF/TKT-1", 1, "\"*\"", alice(), "10");
        setDefault(ALICE_IMF, "IMF/default-members");
        verify(send(HttpMethod.DELETE, "/v1/policy_assignments/platform/IMF/default-members", null, null, "authorization", "admin"), 204);
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("m", ALICE_IMF).status());

            assertEquals("IMF/TKT-1", payerOf("m"));
        }
    }

    // --- Walk behind autoBudgetSelection -------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testZeroSetup() {
        personal();
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("m", ALICE_NO_CLAIM).status());

            assertEquals("ORG/personal", payerOf("m"));
            assertSpent("ORG/personal", "0.5");
        }
    }

    @Test
    @DialConfigLocation(NO_WALK_CONFIG)
    void testWalkTurnedOff() {
        personal();
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("m", ALICE_NO_CLAIM), 403, "no_budget",
                    "No budget is indicated for m. Indicate a budget with the X-DIAL-BUDGET header or set a default budget.");
        }
    }

    // --- Calls with a per-request key resolve the root's payer ---------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationCallsModelUnderTheRootsIndication() {
        ticket();
        projectDefault();
        AtomicReference<Response> inner = new AtomicReference<>();
        app.set(apiKey -> relay(inner, "strong", apiKey));
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("app", ALICE_IMF, BUDGET, "IMF/TKT-1").status());

            assertEquals(200, inner.get().status(), inner.get().body());
            assertEquals("IMF/TKT-1", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testApplicationSendsItsOwnIndication() {
        ticket();
        projectDefault();
        setDefault(ALICE_IMF, "IMF/default-members");
        AtomicReference<Response> inner = new AtomicReference<>();
        app.set(apiKey -> relay(inner, "strong", apiKey, BUDGET, "IMF/TKT-1", COST_OBJECT, "a/b"));
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("app", ALICE_IMF).status());

            assertEquals(200, inner.get().status(), inner.get().body());
            assertEquals("IMF/default-members", payerOf("m"));
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testHeaderCostObjectCarriedToTheModel() {
        ticket();
        AtomicReference<Response> inner = new AtomicReference<>();
        app.set(apiKey -> relay(inner, "strong", apiKey));
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("app", ALICE_NO_PATH, COST_OBJECT, "IMF").status());

            assertEquals(200, inner.get().status(), inner.get().body());
            assertEquals("IMF/TKT-1", payerOf("m"));
            AnalyticsLogContext row = rowOf("m");
            assertEquals("IMF", row.getCostObject());
            assertEquals(CostObject.Source.HEADER, row.getCostObjectSource());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testBudgetWithdrawnMidSession() {
        ticket();
        projectDefault();
        List<Response> inner = new ArrayList<>();
        app.set(apiKey -> {
            AtomicReference<Response> first = new AtomicReference<>();
            relay(first, "strong", apiKey);
            inner.add(first.get());
            verify(send(HttpMethod.DELETE, "/v1/policy_assignments/platform/IMF/TKT-1", null, null, "authorization", "admin"), 204);
            AtomicReference<Response> second = new AtomicReference<>();
            MockResponse answer = relay(second, "strong", apiKey);
            inner.add(second.get());
            return answer;
        });
        try (TestWebServer server = upstreams(null)) {
            chat("app", ALICE_IMF, BUDGET, "IMF/TKT-1");

            assertEquals(200, inner.get(0).status(), inner.get(0).body());
            assertRefused(inner.get(1), 403, "no_budget", null);
        }
    }

    // --- spending-refusals ---------------------------------------------------------------------------------------

    @Test
    @DialConfigLocation(CONFIG)
    void testIndicatedBudgetThroughTierAlias() {
        ticket();
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("hi-end", ALICE_IMF, BUDGET, "IMF/TKT-1"), 403, "budget_not_applicable", "Budget IMF/TKT-1 does not cover hi-end");
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testDefaultNotApplicableInShadowMode() {
        ticket();
        setDefault(ALICE_IMF, "IMF/TKT-1");
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("hi-end", ALICE_IMF).status());

            assertEquals(1, registry.counter("dial_budget_rejections_total", "reason", "default_budget_not_applicable", "mode", "shadow").count());
            assertEquals(BudgetCheck.Outcome.NO_BUDGET, rowOf("m2").getBudgetCheck().outcome());
        } finally {
            Metrics.removeRegistry(registry);
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testMalformedIndicationInShadowMode() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
        try (TestWebServer server = upstreams(null)) {
            assertRefused(chat("m", ALICE_IMF, BUDGET, "x"), 400, "invalid_budget_indication", null);

            assertEquals(1, registry.counter("dial_budget_rejections_total", "reason", "invalid_budget_indication", "mode", "shadow").count());
        } finally {
            Metrics.removeRegistry(registry);
        }
    }

    @Test
    @DialConfigLocation(SHADOW_CONFIG)
    void testIndicatedBudgetNotCoveringInShadowMode() {
        ticket();
        try (TestWebServer server = upstreams(null)) {
            assertEquals(200, chat("hi-end", ALICE_IMF, BUDGET, "IMF/TKT-1").status());

            assertEquals(BudgetCheck.Outcome.NO_BUDGET, rowOf("m2").getBudgetCheck().outcome());
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private Response chat(String deployment, String authorization, String... headers) {
        List<String> all = new ArrayList<>(List.of("content-type", "application/json", "authorization", authorization));
        all.addAll(List.of(headers));
        return send(HttpMethod.POST, "/openai/deployments/" + deployment + "/chat/completions", null, CHAT_BODY, all.toArray(String[]::new));
    }

    private MockResponse relay(AtomicReference<Response> callback, String deployment, String apiKey, String... headers) {
        List<String> all = new ArrayList<>(List.of("content-type", "application/json", "api-key", apiKey));
        all.addAll(List.of(headers));
        Response answer = send(HttpMethod.POST, "/openai/deployments/" + deployment + "/chat/completions", null, CHAT_BODY,
                all.toArray(String[]::new));
        callback.set(answer);
        return TestWebServer.createResponse(answer.status(), answer.body(), "Content-Type", "application/json");
    }

    private TestWebServer upstreams(AtomicInteger upstreamCalls) {
        TestWebServer server = new TestWebServer(4848, request -> {
            if (upstreamCalls != null) {
                upstreamCalls.incrementAndGet();
            }
            String path = Objects.requireNonNull(request.getPath());
            if (path.startsWith("/app")) {
                return app.get().apply(request.getHeader("api-key"));
            }
            if (path.startsWith("/openai/deployments/m")) {
                return TestWebServer.createResponse(200, CHAT_ANSWER, "Content-Type", "application/json");
            }
            return TestWebServer.createResponse(404, "unexpected " + path);
        });
        return server;
    }

    private void ticket() {
        budget("IMF/TKT-1", 10, "[\"strong\"]", alice(), "10");
    }

    private void projectDefault() {
        budget("IMF/default-members", 20, "\"*\"", EVERYONE, "10");
    }

    private void personal() {
        budget("ORG/personal", 30, "\"*\"", EVERYONE, "10");
    }

    private static String alice() {
        return "[ { \"source\": \"sub\", \"function\": \"EQUAL\", \"targets\": [\"alice\"] } ]";
    }

    private void budget(String instanceId, int priority, String object, String subject, String amount) {
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        String policy = "policy-" + instanceId.replace('/', '-');
        verify(send(HttpMethod.PUT, "/v1/policies/platform/" + policy, null, """
                { "priority": %d, "kind": "budget", "quantity": "cost", "object": %s, "per": ["subject"],
                  "cost": { "day": %s, "month": %s } }
                """.formatted(priority, object, amount, amount), "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, "/v1/policy_assignments/platform/" + instanceId, null, """
                { "policy": "%s", "costObject": "%s", "subject": %s }
                """.formatted(policy, costObject, subject), "authorization", "admin"), 200);
    }

    private void setDefault(String authorization, String instanceId) {
        verify(send(HttpMethod.PUT, "/v1/user/budgets/default", null, "{\"budget\": \"" + instanceId + "\"}",
                "content-type", "application/json", "authorization", authorization), 200);
    }

    @SneakyThrows
    private void spend(String instanceId, String userId, String model, String dollars) {
        Config config = dial.getProxy().getConfigStore().get();
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        BudgetInstance instance = findInstance(config, instanceId);
        new BudgetCounterStore(dial.getProxy().getTaskExecutor(), dial.getProxy().getResourceService())
                .charge(costObject, instance, userId, model, new BigDecimal(dollars), System.currentTimeMillis(), config.getRateLimitSchedule())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @SneakyThrows
    private void assertSpent(String instanceId, String dollars) {
        Config config = dial.getProxy().getConfigStore().get();
        String costObject = instanceId.substring(0, instanceId.indexOf('/'));
        BudgetCounterStore store = new BudgetCounterStore(dial.getProxy().getTaskExecutor(), dial.getProxy().getResourceService());
        awaitCondition(() -> {
            BigDecimal day = store.loadBlocking(costObject, findInstance(config, instanceId), "alice", System.currentTimeMillis(),
                    config.getRateLimitSchedule()).day();
            return day.compareTo(new BigDecimal(dollars)) == 0;
        });
    }

    private static BudgetInstance findInstance(Config config, String instanceId) {
        return config.getCostObjectIndex().getInstances(instanceId.substring(0, instanceId.indexOf('/'))).stream()
                .filter(candidate -> candidate.id().equals(instanceId))
                .findFirst()
                .orElseThrow();
    }

    private String payerOf(String model) {
        awaitCondition(() -> findRow(model) != null && findRow(model).getBudgetCheck().payer() != null);
        return findRow(model).getBudgetCheck().payer().instance().id();
    }

    private AnalyticsLogContext rowOf(String model) {
        awaitCondition(() -> findRow(model) != null);
        return findRow(model);
    }

    private AnalyticsLogContext findRow(String model) {
        List<AnalyticsLogContext> rows = List.copyOf(analyticsRows);
        for (int i = rows.size() - 1; i >= 0; i--) {
            AnalyticsLogContext row = rows.get(i);
            if (model.equals(row.getDeploymentName()) && row.getBudgetCheck() != null) {
                return row;
            }
        }
        return null;
    }

    @SneakyThrows
    private static void assertRefused(Response response, int status, String code, String message) {
        assertNotNull(response);
        assertEquals(status, response.status(), response.body());
        var error = ProxyUtil.MAPPER.readTree(response.body()).get("error");
        assertEquals(code, error.get("code").asText(), response.body());
        if (message != null) {
            assertEquals(message, error.get("message").asText());
        }
    }
}
