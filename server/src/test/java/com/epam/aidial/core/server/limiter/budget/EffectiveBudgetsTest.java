package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.config.policy.CostObjectIndex;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyKind;
import com.epam.aidial.core.config.policy.PolicyObjectSet;
import com.epam.aidial.core.config.policy.PolicyQuantity;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectiveBudgetsTest {

    private static final Instant NOW = Instant.parse("2026-11-03T10:00:00Z");
    private static final String COST_OBJECT = "projA";
    private static final String COUNTER = "dial_budget_ambiguous_priority_total";

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        Metrics.addRegistry(registry);
    }

    @AfterEach
    void tearDown() {
        Metrics.removeRegistry(registry);
    }

    @Test
    void testAllMatchingBudgetsAreCandidatesInPriorityThenIdOrder() {
        Map<String, Policy> policies = new LinkedHashMap<>();
        policies.put("tier-b", policy(2, PolicyObjectSet.all()));
        policies.put("tier-a", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = new LinkedHashMap<>();
        assignments.put("projA/g1", assignment("tier-b", null, anyone()));
        assignments.put("projA/g2", assignment("tier-a", null, anyone()));

        List<String> ids = ids(resolve(policies, assignments, user("alice"), "gpt"));

        assertEquals(List.of("projA/g2", "projA/g1"), ids);
    }

    @Test
    void testTwoTiersForOneUserBothMatch() {
        // trace 15
        Map<String, Policy> policies = Map.of("base", policy(1, PolicyObjectSet.all()), "extra", policy(2, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/base-alice", assignment("base", null, equal("sub", "alice")),
                "projA/extra-alice", assignment("extra", null, equal("sub", "alice")));

        assertEquals(List.of("projA/base-alice", "projA/extra-alice"), ids(resolve(policies, assignments, user("alice"), "gpt")));
    }

    @Test
    void testOrderIsStableWhateverTheIndexOrder() {
        Map<String, Policy> policies = Map.of("p", policy(1, PolicyObjectSet.all()));
        List<String> assignmentIds = new ArrayList<>(List.of("projA/c", "projA/a", "projA/b"));
        Collections.shuffle(assignmentIds);
        Map<String, PolicyAssignment> assignments = new LinkedHashMap<>();
        assignmentIds.forEach(id -> assignments.put(id, assignment("p", null, anyone())));

        assertEquals(List.of("projA/a", "projA/b", "projA/c"), ids(resolve(policies, assignments, user("alice"), "gpt")));
    }

    @Test
    void testOwnerChangesTheOrder() {
        // trace 12
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/g1", assignment("one", null, anyone()),
                "projA/g2", assignment("two", null, anyone()));

        Map<String, Policy> before = Map.of("one", policy(1, PolicyObjectSet.all()), "two", policy(2, PolicyObjectSet.all()));
        Map<String, Policy> after = Map.of("one", policy(3, PolicyObjectSet.all()), "two", policy(2, PolicyObjectSet.all()));

        assertEquals(List.of("projA/g1", "projA/g2"), ids(resolve(before, assignments, user("alice"), "gpt")));
        assertEquals(List.of("projA/g2", "projA/g1"), ids(resolve(after, assignments, user("alice"), "gpt")));
    }

    @Test
    void testEqualPrioritiesOfDifferentPoliciesAreMetered() {
        // trace 13
        Map<String, Policy> policies = Map.of("one", policy(1, PolicyObjectSet.all()), "two", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/g2", assignment("two", null, anyone()),
                "projA/g1", assignment("one", null, anyone()));

        assertEquals(List.of("projA/g1", "projA/g2"), ids(resolve(policies, assignments, user("alice"), "gpt")));
        assertEquals(1, registry.counter(COUNTER).count());
    }

    @Test
    void testEqualPrioritiesOfOnePolicyAreNotMetered() {
        Map<String, Policy> policies = Map.of("one", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/g1", assignment("one", null, anyone()),
                "projA/g2", assignment("one", null, anyone()));

        assertEquals(2, resolve(policies, assignments, user("alice"), "gpt").size());
        assertEquals(0, registry.counter(COUNTER).count());
    }

    @Test
    void testEqualPrioritiesOfBudgetsThatDoNotBothMatchAreNotMetered() {
        Map<String, Policy> policies = Map.of("one", policy(1, PolicyObjectSet.all()), "two", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/g1", assignment("one", null, equal("sub", "alice")),
                "projA/g2", assignment("two", null, equal("sub", "bob")));

        assertEquals(List.of("projA/g1"), ids(resolve(policies, assignments, user("alice"), "gpt")));
        assertEquals(0, registry.counter(COUNTER).count());
    }

    @Test
    void testExpiredAssignmentIsSkipped() {
        Map<String, Policy> policies = Map.of("p", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/past", assignment("p", "2026-11-03T10:00:00Z", anyone()),
                "projA/future", assignment("p", "2026-11-03T10:00:01Z", anyone()),
                "projA/forever", assignment("p", null, anyone()));

        assertEquals(List.of("projA/forever", "projA/future"), ids(resolve(policies, assignments, user("alice"), "gpt")));
    }

    @Test
    void testExpiryIsComparedAsAnInstant() {
        Map<String, Policy> policies = Map.of("p", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/g1", assignment("p", "2026-11-03T11:00:00+02:00", anyone()));

        assertTrue(resolve(policies, assignments, user("alice"), "gpt").isEmpty());
    }

    @Test
    void testModelOutsideObjectSetIsSkipped() {
        Map<String, Policy> policies = Map.of(
                "some", policy(1, PolicyObjectSet.of(List.of("gpt"))),
                "all", policy(2, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/g1", assignment("some", null, anyone()),
                "projA/g2", assignment("all", null, anyone()));

        assertEquals(List.of("projA/g1", "projA/g2"), ids(resolve(policies, assignments, user("alice"), "gpt")));
        assertEquals(List.of("projA/g2"), ids(resolve(policies, assignments, user("alice"), "claude")));
    }

    @Test
    void testSubjectFunctions() {
        assertMatched(rule("sub", PolicySubjectRule.Function.TRUE), true);
        assertMatched(rule("sub", PolicySubjectRule.Function.FALSE), false);
        assertMatched(rule("sub", PolicySubjectRule.Function.EQUAL, "ALICE"), true);
        assertMatched(rule("sub", PolicySubjectRule.Function.EQUAL, "bob"), false);
        assertMatched(rule("sub", PolicySubjectRule.Function.CONTAIN, "lic"), true);
        assertMatched(rule("sub", PolicySubjectRule.Function.CONTAIN, "xyz"), false);
        assertMatched(rule("sub", PolicySubjectRule.Function.REGEX, "al.*"), true);
        assertMatched(rule("sub", PolicySubjectRule.Function.REGEX, "lic"), false);
        assertMatched(rule("roles", PolicySubjectRule.Function.EQUAL, "staff"), true);
        assertMatched(rule("missing", PolicySubjectRule.Function.TRUE), false);
    }

    @Test
    void testSubjectRulesAreOrComposed() {
        assertMatchedAny(List.of(rule("sub", PolicySubjectRule.Function.EQUAL, "bob"),
                rule("roles", PolicySubjectRule.Function.EQUAL, "staff")), true);
        assertMatchedAny(List.of(rule("sub", PolicySubjectRule.Function.EQUAL, "bob"),
                rule("roles", PolicySubjectRule.Function.EQUAL, "guest")), false);
    }

    @Test
    void testApiKeyCallerMatchesNothing() {
        Map<String, Policy> policies = Map.of("p", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of("projA/g1", assignment("p", null, anyone()));

        assertTrue(resolve(policies, assignments, null, "gpt").isEmpty());
    }

    @Test
    void testSelfApplyingPolicyIsCandidate() {
        Policy policy = policy(1, PolicyObjectSet.all());
        policy.setCostObject(COST_OBJECT);
        policy.setSubject(anyone());

        assertEquals(List.of("projA/self"), ids(resolve(Map.of("projA/self", policy), Map.of(), user("alice"), "gpt")));
    }

    @Test
    void testBudgetWithoutSubjectIsSkipped() {
        Map<String, Policy> policies = Map.of("p", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of("projA/g1", assignment("p", null, null));

        assertTrue(resolve(policies, assignments, user("alice"), "gpt").isEmpty());
    }

    @Test
    void testOtherCostObjectIsNotConsidered() {
        Map<String, Policy> policies = Map.of("p", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of("projB/g1", assignment("p", null, anyone()));

        assertTrue(resolve(policies, assignments, user("alice"), "gpt").isEmpty());
    }

    @Test
    void testAllMatchingBudgetsAreListedByCostObjectAndPriority() {
        Map<String, Policy> policies = Map.of(
                "low", costPolicy(2, PolicyObjectSet.of(List.of("gpt"))),
                "high", costPolicy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/g1", assignment("low", null, anyone()),
                "projA/g2", assignment("high", null, anyone()),
                "projB/g1", assignment("low", null, anyone()),
                "projC/g1", assignment("low", null, equal("sub", "bob")));

        List<BudgetInstance> all = EffectiveBudgets
                .resolveAll(CostObjectIndex.build(policies, assignments), user("alice"), NOW);

        assertEquals(List.of("projA/g2", "projA/g1", "projB/g1"), ids(all));
    }

    @Test
    void testReportingSkipsExpiredAndNonCostAndApiKey() {
        Policy tokens = costPolicy(1, PolicyObjectSet.all());
        tokens.setQuantity(PolicyQuantity.TOKENS);
        Map<String, Policy> policies = Map.of("cost", costPolicy(1, PolicyObjectSet.all()), "tokens", tokens);
        Map<String, PolicyAssignment> assignments = Map.of(
                "projA/expired", assignment("cost", "2026-11-03T09:00:00Z", anyone()),
                "projB/tokens", assignment("tokens", null, anyone()));
        CostObjectIndex index = CostObjectIndex.build(policies, assignments);

        assertTrue(EffectiveBudgets.resolveAll(index, user("alice"), NOW).isEmpty());
        assertTrue(EffectiveBudgets.resolveAll(index, null, NOW).isEmpty());
    }

    private void assertMatched(PolicySubjectRule rule, boolean expected) {
        assertMatchedAny(List.of(rule), expected);
    }

    private void assertMatchedAny(List<PolicySubjectRule> rules, boolean expected) {
        Map<String, Policy> policies = Map.of("p", policy(1, PolicyObjectSet.all()));
        Map<String, PolicyAssignment> assignments = Map.of("projA/g1", assignment("p", null, rules));

        assertEquals(expected, !resolve(policies, assignments, user("alice"), "gpt").isEmpty());
    }

    private static List<BudgetInstance> resolve(Map<String, Policy> policies, Map<String, PolicyAssignment> assignments,
                                                ExtractedClaims claims, String model) {
        return EffectiveBudgets.resolve(CostObjectIndex.build(policies, assignments), COST_OBJECT, claims, model, NOW);
    }

    private static List<String> ids(List<BudgetInstance> instances) {
        return instances.stream().map(BudgetInstance::id).toList();
    }

    private static Policy policy(int priority, PolicyObjectSet object) {
        Policy policy = new Policy();
        policy.setPriority(priority);
        policy.setKind(PolicyKind.BUDGET);
        policy.setObject(object);
        return policy;
    }

    private static Policy costPolicy(int priority, PolicyObjectSet object) {
        Policy policy = policy(priority, object);
        policy.setQuantity(PolicyQuantity.COST);
        return policy;
    }

    private static PolicyAssignment assignment(String policy, String expiresAt, List<PolicySubjectRule> subject) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicy(policy);
        assignment.setExpiresAt(expiresAt);
        assignment.setSubject(subject);
        return assignment;
    }

    private static List<PolicySubjectRule> anyone() {
        return List.of(rule("sub", PolicySubjectRule.Function.TRUE));
    }

    private static List<PolicySubjectRule> equal(String source, String target) {
        return List.of(rule(source, PolicySubjectRule.Function.EQUAL, target));
    }

    private static PolicySubjectRule rule(String source, PolicySubjectRule.Function function, String... targets) {
        PolicySubjectRule rule = new PolicySubjectRule();
        rule.setSource(source);
        rule.setFunction(function);
        rule.setTargets(List.of(targets));
        return rule;
    }

    private static ExtractedClaims user(String sub) {
        ObjectNode claims = ProxyUtil.MAPPER.createObjectNode();
        claims.put("sub", sub);
        return new ExtractedClaims(sub, List.of("staff"), "hash", claims, COST_OBJECT, sub);
    }
}
