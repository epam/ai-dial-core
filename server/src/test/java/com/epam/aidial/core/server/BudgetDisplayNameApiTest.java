package com.epam.aidial.core.server;

import com.epam.aidial.core.server.limiter.budget.BudgetCounterStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BudgetDisplayNameApiTest extends ResourceBaseTest {

    private static final String CONFIG = "dial-config/user-budgets.json";
    private static final String POLICIES = "/v1/policies/platform/";
    private static final List<String> STATS_PATHS = List.of("/v1/user/limits", "/v1/user/usage");

    @Test
    @DialConfigLocation(CONFIG)
    void testPolicyDisplayNameSurvivesReload() {
        putPolicy("engineering", policy("Engineering Pro"));
        verify(admin(HttpMethod.POST, "/v1/ops/config/reload", null), 200);

        JsonNode stored = adminGet(POLICIES + "engineering");
        assertEquals("Engineering Pro", stored.get("displayName").asText());
        assertEquals("engineering", stored.get("id").asText());
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testPolicyDisplayNameInManifest() {
        String body = """
                { "manifests": [{ "kind": "Policy", "name": "policies/platform/engineering", "spec": %s }] }
                """.formatted(policy("Engineering Pro"));

        Response validated = admin(HttpMethod.POST, "/v1/admin/validate", body);
        verify(validated, 200);
        assertEquals(1, json(validated.body()).get("valid").asInt());
        Response applied = admin(HttpMethod.POST, "/v1/admin/apply", body);
        verify(applied, 200);
        assertEquals(1, json(applied.body()).get("applied").asInt());
        assertEquals("Engineering Pro", adminGet(POLICIES + "engineering").get("displayName").asText());
    }

    @Test
    @DialConfigLocation("dial-config/named-user-budget.json")
    void testNamedFilePolicy() {
        Response reloaded = admin(HttpMethod.POST, "/v1/ops/config/reload", null);
        verify(reloaded, 200);
        JsonNode stored = json(reloaded.body()).get("policies").get("ORG/personal");
        assertEquals("Personal Sandbox", stored.get("displayName").asText());
        for (String path : STATS_PATHS) {
            JsonNode budgets = budgets(path);
            assertEquals(1, budgets.size());
            assertEquals("Personal Sandbox", budgets.get(0).get("displayName").asText());
            assertEquals("ORG/personal", budgets.get(0).get("policy").asText());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testMissingPolicyDisplayName() {
        ObjectNode policy = policy(null);
        policy.remove("displayName");
        putPolicy("projA/engineering", policy);
        assign("projA/engineering", "grant");

        assertFalse(adminGet(POLICIES + "projA/engineering").has("displayName"));
        assertFallbackName();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t "})
    @DialConfigLocation(CONFIG)
    void testBlankPolicyDisplayName(String displayName) {
        putPolicy("projA/engineering", policy(displayName));
        assign("projA/engineering", "grant");

        JsonNode stored = adminGet(POLICIES + "projA/engineering");
        if (displayName == null) {
            assertFalse(stored.has("displayName"));
        } else {
            assertEquals(displayName, stored.get("displayName").asText());
        }
        assertFallbackName();
    }

    @Test
    @DialConfigLocation(CONFIG)
    void testNamedAssignmentsKeepPriorityOrder() {
        putPolicy("engineering", policy(" Engineering Pro "));
        putPolicy("team", policy("Team Budget").put("priority", 20));
        assign("team", "first");
        assign("engineering", "second");

        for (String path : STATS_PATHS) {
            JsonNode budgets = budgets(path);
            assertEquals(2, budgets.size());
            assertEquals("engineering", budgets.get(0).get("policy").asText());
            assertEquals(" Engineering Pro ", budgets.get(0).get("displayName").asText());
            assertEquals("team", budgets.get(1).get("policy").asText());
            assertEquals("Team Budget", budgets.get(1).get("displayName").asText());
            assertEquals("projA", budgets.get(0).get("costObject").asText());
            assertEquals("projA", budgets.get(1).get("costObject").asText());
        }
    }

    @Test
    @DialConfigLocation(CONFIG)
    @SneakyThrows
    void testRenamePreservesSpend() {
        ObjectNode policy = policy("Engineering Pro");
        putPolicy("engineering", policy);
        assign("engineering", "grant");
        var proxy = dial.getProxy();
        var config = proxy.getConfigStore().get();
        var instance = config.getCostObjectIndex().getInstances("projA").get(0);
        new BudgetCounterStore(proxy.getTaskExecutor(), proxy.getResourceService())
                .charge("projA", instance, "user", "budget-model", new BigDecimal("4.25"),
                        System.currentTimeMillis(), config.getRateLimitSchedule())
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        ObjectNode before = (ObjectNode) budgets(STATS_PATHS.get(0)).get(0);
        assertEquals(0, new BigDecimal("4.25").compareTo(before.get("month").get("used").decimalValue()));

        putPolicy("engineering", policy.put("displayName", "Engineering Plus"));

        before.put("displayName", "Engineering Plus");
        for (String path : STATS_PATHS) {
            assertEquals(before, budgets(path).get(0));
        }
    }

    private void assertFallbackName() {
        for (String path : STATS_PATHS) {
            JsonNode budget = budgets(path).get(0);
            assertEquals("projA/engineering", budget.get("displayName").asText());
            assertEquals("projA/engineering", budget.get("policy").asText());
        }
    }

    private static ObjectNode policy(String displayName) {
        ObjectNode policy = (ObjectNode) json("""
                { "priority": 10, "kind": "budget", "quantity": "cost", "object": "*", "per": ["subject"],
                  "cost": { "day": 10, "month": 200 } }
                """);
        return policy.put("displayName", displayName);
    }

    private void putPolicy(String path, ObjectNode policy) {
        verify(admin(HttpMethod.PUT, POLICIES + path, policy.toString()), 200);
    }

    private void assign(String policy, String id) {
        verify(admin(HttpMethod.PUT, "/v1/policy_assignments/platform/projA/" + id, """
                { "policy": "%s", "costObject": "projA",
                  "subject": [{ "source": "title", "function": "EQUAL", "targets": ["Manager"] }] }
                """.formatted(policy)), 200);
    }

    private Response admin(HttpMethod method, String path, String body) {
        return send(method, path, null, body, "authorization", "admin");
    }

    private JsonNode adminGet(String path) {
        Response response = admin(HttpMethod.GET, path, null);
        verify(response, 200);
        return json(response.body());
    }

    private JsonNode budgets(String path) {
        Response response = send(HttpMethod.GET, path, null, null, "authorization", "user");
        verify(response, 200);
        return json(response.body()).get("budgets");
    }

    @SneakyThrows
    private static JsonNode json(String body) {
        return ProxyUtil.MAPPER.readTree(body);
    }
}
