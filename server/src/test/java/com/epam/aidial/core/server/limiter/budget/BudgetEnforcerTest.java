package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.SpendingLimitMode;
import com.epam.aidial.core.config.SpendingLimits;
import com.epam.aidial.core.config.policy.CostObjectIndex;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAmounts;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyKind;
import com.epam.aidial.core.config.policy.PolicyObjectSet;
import com.epam.aidial.core.config.policy.PolicyQuantity;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BudgetEnforcerTest {

    private static final long NOW = Instant.parse("2026-11-03T10:00:00Z").toEpochMilli();
    private static final long DAY_RESET = Instant.parse("2026-11-04T00:00:00Z").toEpochMilli();
    private static final long MONTH_RESET = Instant.parse("2026-12-01T00:00:00Z").toEpochMilli();
    private static final RateLimitSchedule SCHEDULE = new RateLimitSchedule();

    private BudgetCounterStore store;
    private BudgetEnforcer enforcer;
    private Config config;
    private ProxyContext context;
    private Model model;

    @BeforeEach
    void setUp() {
        store = mock(BudgetCounterStore.class);
        enforcer = new BudgetEnforcer(store);
        config = new Config();
        config.getSpendingLimits().setMode(SpendingLimitMode.ENFORCE);
        context = mock(ProxyContext.class);
        when(context.getConfig()).thenReturn(config);
        when(context.getExtractedClaims()).thenReturn(claims());
        model = new Model();
        model.setName("gpt");
    }

    @Test
    void testFirstBudgetWithRoomPaysAndFallsThrough() {
        // traces 1 and 2
        index(Map.of("first", policy(1, "1", null), "second", policy(2, "5", null)),
                Map.of("ORG/a", assignment("first"), "ORG/b", assignment("second")));
        usage("ORG/a", "1", "1");
        usage("ORG/b", "0", "0");

        RateLimitResult result = enforcer.check(context, model, NOW, SCHEDULE).result();

        assertEquals(HttpStatus.OK, result.status());
        ArgumentCaptorHolder.verifyPayer(context, "ORG/b", "ORG", "alice");
    }

    @Test
    void testEveryWindowOfTheBudgetMustHaveRoom() {
        index(Map.of("p", policy(1, "10", "20")), Map.of("ORG/a", assignment("p")));
        usage("ORG/a", "1", "20");

        RateLimitResult result = enforcer.check(context, model, NOW, SCHEDULE).result();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, result.status());
    }

    @Test
    void testAllDryRejectsWithRetryAfterOfTheEarliestBudgetToRecover() {
        // trace 4
        index(Map.of("first", policy(1, "1", "5"), "second", policy(2, "1", null)),
                Map.of("ORG/a", assignment("first"), "ORG/b", assignment("second")));
        usage("ORG/a", "1", "5");
        usage("ORG/b", "2", "2");

        RateLimitResult result = enforcer.check(context, model, NOW, SCHEDULE).result();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, result.status());
        // the first budget needs its month to reset, the second only its day
        assertEquals((DAY_RESET - NOW) / 1000, result.replyAfterSeconds());
        assertTrue(MONTH_RESET > DAY_RESET);
    }

    @Test
    void testModelOutsideEveryBudgetHasNoBudget() {
        // trace 3
        Policy other = policy(1, "1", null);
        other.setObject(PolicyObjectSet.of(List.of("claude")));
        index(Map.of("p", other), Map.of("ORG/a", assignment("p")));

        RateLimitResult result = enforcer.check(context, model, NOW, SCHEDULE).result();

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, result.status());
        assertEquals(-1, result.replyAfterSeconds());
    }

    @Test
    void testProjectWithoutAssignmentHasNoBudget() {
        // trace 5
        when(context.getUserProject()).thenReturn("projA");
        index(Map.of("p", policy(1, "1", null)), Map.of("ORG/a", assignment("p")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, enforcer.check(context, model, NOW, SCHEDULE).result().status());
    }

    @Test
    void testShadowNeverRejectsButStillPicksThePayer() {
        // trace 9
        config.getSpendingLimits().setMode(SpendingLimitMode.SHADOW);
        index(Map.of("p", policy(1, "1", null)), Map.of("ORG/a", assignment("p")));
        usage("ORG/a", "5", "5");

        assertEquals(HttpStatus.OK, enforcer.check(context, model, NOW, SCHEDULE).result().status());

        config.setCostObjectIndex(CostObjectIndex.EMPTY);
        assertEquals(HttpStatus.OK, enforcer.check(context, model, NOW, SCHEDULE).result().status());
    }

    @Test
    void testShadowPayerIsSetWhenThereIsRoom() {
        config.getSpendingLimits().setMode(SpendingLimitMode.SHADOW);
        index(Map.of("p", policy(1, "1", null)), Map.of("ORG/a", assignment("p")));
        usage("ORG/a", "0", "0");

        enforcer.check(context, model, NOW, SCHEDULE).result();

        ArgumentCaptorHolder.verifyPayer(context, "ORG/a", "ORG", "alice");
    }

    @Test
    void testBudgetOfAnotherQuantityIsSkipped() {
        Policy tokens = policy(1, "1", null);
        tokens.setQuantity(PolicyQuantity.TOKENS);
        index(Map.of("p", tokens), Map.of("ORG/a", assignment("p")));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, enforcer.check(context, model, NOW, SCHEDULE).result().status());
    }

    @Test
    void testCostObjectIsTheProjectOfTheCallOtherwiseTheConfiguredDefault() {
        // traces 6 and 14
        assertEquals("ORG", BudgetEnforcer.resolveCostObject(context, config.getSpendingLimits()));

        config.getSpendingLimits().setDefaultCostObject("PERSONAL");
        assertEquals("PERSONAL", BudgetEnforcer.resolveCostObject(context, config.getSpendingLimits()));

        Key key = new Key();
        key.setProject("keyProject");
        when(context.getKey()).thenReturn(key);
        assertEquals("keyProject", BudgetEnforcer.resolveCostObject(context, config.getSpendingLimits()));

        when(context.getUserProject()).thenReturn("jwtProject");
        assertEquals("jwtProject", BudgetEnforcer.resolveCostObject(context, config.getSpendingLimits()));
    }

    @Test
    void testApplicability() {
        assertTrue(BudgetEnforcer.isApplicable(context, model));
        assertTrue(BudgetEnforcer.isEnforced(context, model));

        // trace 7: an API key caller has no claims
        when(context.getExtractedClaims()).thenReturn(null);
        assertFalse(BudgetEnforcer.isApplicable(context, model));
        when(context.getExtractedClaims()).thenReturn(claims());

        assertFalse(BudgetEnforcer.isApplicable(context, new com.epam.aidial.core.config.Application()));

        config.getSpendingLimits().setMode(SpendingLimitMode.SHADOW);
        assertTrue(BudgetEnforcer.isApplicable(context, model));
        assertFalse(BudgetEnforcer.isEnforced(context, model));

        config.getSpendingLimits().setMode(SpendingLimitMode.OFF);
        assertFalse(BudgetEnforcer.isApplicable(context, model));
    }

    @Test
    void testChargeGoesToThePayersRowOfTheModel() {
        index(Map.of("p", policy(1, "1", null)), Map.of("ORG/a", assignment("p")));
        var instance = config.getCostObjectIndex().getInstances("ORG").get(0);
        BudgetPayer payer = new BudgetPayer("ORG", instance, "alice");
        when(store.charge(any(), any(), any(), any(), any(), anyLong(), any())).thenReturn(Future.succeededFuture());

        enforcer.charge(payer, "gpt", BigDecimal.TEN, NOW, SCHEDULE);

        verify(store).charge("ORG", instance, "alice", "gpt", BigDecimal.TEN, NOW, SCHEDULE);
    }

    @Test
    void testDefaultSettingsAreOff() {
        assertEquals(SpendingLimitMode.OFF, new SpendingLimits().getMode());
        assertNull(context.getBudgetPayer());
    }

    private void index(Map<String, Policy> policies, Map<String, PolicyAssignment> assignments) {
        config.setCostObjectIndex(CostObjectIndex.build(policies, assignments));
    }

    private void usage(String instanceId, String day, String month) {
        var instance = config.getCostObjectIndex().getInstances("ORG").stream()
                .filter(candidate -> candidate.id().equals(instanceId)).findFirst().orElseThrow();
        when(store.load(eq("ORG"), eq(instance), eq("alice"), eq(NOW), any())).thenReturn(Future.succeededFuture(
                new BudgetUsage(new BigDecimal(day), new BigDecimal(month), DAY_RESET, MONTH_RESET)));
    }

    private static Policy policy(int priority, String day, String month) {
        Policy policy = new Policy();
        policy.setPriority(priority);
        policy.setKind(PolicyKind.BUDGET);
        policy.setQuantity(PolicyQuantity.COST);
        policy.setObject(PolicyObjectSet.all());
        PolicyAmounts cost = new PolicyAmounts();
        cost.setDay(day == null ? null : new BigDecimal(day));
        cost.setMonth(month == null ? null : new BigDecimal(month));
        policy.setCost(cost);
        return policy;
    }

    private static PolicyAssignment assignment(String policy) {
        PolicySubjectRule rule = new PolicySubjectRule();
        rule.setSource("sub");
        rule.setFunction(PolicySubjectRule.Function.TRUE);
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicy(policy);
        assignment.setSubject(List.of(rule));
        return assignment;
    }

    private static ExtractedClaims claims() {
        return new ExtractedClaims("alice", List.of("staff"), "hash", com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode().put("sub", "alice"),
                null, "Alice");
    }

    private static final class ArgumentCaptorHolder {
        static void verifyPayer(ProxyContext context, String instanceId, String costObject, String userId) {
            var captor = org.mockito.ArgumentCaptor.forClass(BudgetPayer.class);
            verify(context).setBudgetPayer(captor.capture());
            assertEquals(instanceId, captor.getValue().instance().id());
            assertEquals(costObject, captor.getValue().costObject());
            assertEquals(userId, captor.getValue().userId());
        }
    }
}
