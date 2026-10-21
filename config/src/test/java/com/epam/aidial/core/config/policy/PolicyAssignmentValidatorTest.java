package com.epam.aidial.core.config.policy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyAssignmentValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Policy validPolicy() {
        Policy policy = new Policy();
        policy.setPriority(10);
        policy.setKind(PolicyKind.BUDGET);
        policy.setQuantity(PolicyQuantity.COST);
        policy.setObject(PolicyObjectSet.all());
        policy.setPer(List.of("subject"));
        PolicyAmounts cost = new PolicyAmounts();
        cost.setMonth(BigDecimal.valueOf(500));
        policy.setCost(cost);
        return policy;
    }

    private static PolicySubjectRule rule(String source, PolicySubjectRule.Function function, String... targets) {
        PolicySubjectRule rule = new PolicySubjectRule();
        rule.setSource(source);
        rule.setFunction(function);
        rule.setTargets(targets.length == 0 ? null : List.of(targets));
        return rule;
    }

    @Test
    void testValidPolicy() {
        assertNull(PolicyAssignmentValidator.validatePolicy(validPolicy()));
    }

    @Test
    void testPolicyRequiresKindQuantityPriorityObjectPerAndAmount() {
        Policy policy = validPolicy();
        policy.setKind(null);
        assertEquals("kind is required", PolicyAssignmentValidator.validatePolicy(policy));

        policy = validPolicy();
        policy.setQuantity(null);
        assertEquals("quantity is required", PolicyAssignmentValidator.validatePolicy(policy));

        policy = validPolicy();
        policy.setPriority(null);
        assertTrue(PolicyAssignmentValidator.validatePolicy(policy).startsWith("priority is required"));

        policy = validPolicy();
        policy.setObject(null);
        assertEquals("object is required", PolicyAssignmentValidator.validatePolicy(policy));

        policy = validPolicy();
        policy.setPer(null);
        assertEquals("per is required", PolicyAssignmentValidator.validatePolicy(policy));

        policy = validPolicy();
        policy.setCost(new PolicyAmounts());
        assertTrue(PolicyAssignmentValidator.validatePolicy(policy).startsWith("cost must set an amount"));
    }

    @Test
    void testBudgetQuantityMustBeCost() {
        Policy policy = validPolicy();
        policy.setQuantity(PolicyQuantity.TOKENS);
        assertEquals("a budget must have quantity 'cost', got 'tokens'", PolicyAssignmentValidator.validatePolicy(policy));
    }

    @Test
    void testAmountMustBePositive() {
        Policy policy = validPolicy();
        policy.getCost().setMonth(BigDecimal.ZERO);
        assertEquals("cost.month must be greater than 0", PolicyAssignmentValidator.validatePolicy(policy));

        policy = validPolicy();
        policy.getCost().setDay(BigDecimal.valueOf(-1));
        assertEquals("cost.day must be greater than 0", PolicyAssignmentValidator.validatePolicy(policy));
    }

    @Test
    void testPerOtherThanSubjectIsNotSupported() {
        Policy policy = validPolicy();
        policy.setPer(List.of("subject", "model"));
        assertTrue(PolicyAssignmentValidator.validatePolicy(policy).contains("not supported yet"));

        policy.setPer(List.of("project"));
        assertTrue(PolicyAssignmentValidator.validatePolicy(policy).contains("not supported yet"));
    }

    @Test
    void testReservedFieldsAreRejected() throws Exception {
        for (String field : List.of("attachedTo", "harness", "broker", "packages")) {
            Policy policy = validPolicy();
            switch (field) {
                case "attachedTo" -> policy.setAttachedTo(MAPPER.readTree("\"x\""));
                case "harness" -> policy.setHarness(MAPPER.readTree("[\"x\"]"));
                case "broker" -> policy.setBroker(MAPPER.readTree("{}"));
                default -> policy.setPackages(MAPPER.readTree("[\"x\"]"));
            }
            assertEquals("'" + field + "' is not supported yet", PolicyAssignmentValidator.validatePolicy(policy));
        }
        Policy policy = validPolicy();
        policy.setHarness(MAPPER.readTree("null"));
        assertNull(PolicyAssignmentValidator.validatePolicy(policy));
    }

    @Test
    void testSubjectRules() {
        assertTrue(PolicyAssignmentValidator.validateSubject(List.of()).contains("at least one rule"));
        assertTrue(PolicyAssignmentValidator.validateSubject(List.of(rule(" ", PolicySubjectRule.Function.TRUE))).contains("source"));
        assertTrue(PolicyAssignmentValidator.validateSubject(List.of(rule("sub", null))).contains("function"));
        assertTrue(PolicyAssignmentValidator.validateSubject(List.of(rule("sub", PolicySubjectRule.Function.EQUAL))).contains("targets"));
        assertTrue(PolicyAssignmentValidator.validateSubject(List.of(rule("sub", PolicySubjectRule.Function.CONTAIN, ""))).contains("targets"));
        assertTrue(PolicyAssignmentValidator.validateSubject(List.of(rule("sub", PolicySubjectRule.Function.REGEX, "(unclosed")))
                .contains("invalid pattern"));
        assertNull(PolicyAssignmentValidator.validateSubject(List.of(
                rule("sub", PolicySubjectRule.Function.TRUE),
                rule("roles", PolicySubjectRule.Function.FALSE),
                rule("roles", PolicySubjectRule.Function.REGEX, "costLimit-\\d+"),
                rule("sub", PolicySubjectRule.Function.EQUAL, "alice", "bob"))));
    }

    @Test
    void testAssignment() {
        PolicyAssignment assignment = new PolicyAssignment();
        assertEquals("policy is required", PolicyAssignmentValidator.validateAssignment(assignment));

        assignment.setPolicy("anthropic-pro-500");
        assertNull(PolicyAssignmentValidator.validateAssignment(assignment));

        assignment.setExpiresAt("tomorrow");
        assertTrue(PolicyAssignmentValidator.validateAssignment(assignment).startsWith("expiresAt must be an ISO-8601"));

        assignment.setExpiresAt("2099-01-01T00:00:00Z");
        assertNull(PolicyAssignmentValidator.validateAssignment(assignment));

        assignment.setSubject(List.of());
        assertTrue(PolicyAssignmentValidator.validateAssignment(assignment).contains("at least one rule"));
    }

    @Test
    void testPolicyOnlyFieldsOnAssignment() throws Exception {
        assertEquals("'priority' belongs to the policy, not to an assignment",
                PolicyAssignmentValidator.rejectPolicyFieldsOn(MAPPER.readTree("{\"policy\": \"p\", \"priority\": 1}")));
        assertEquals("'cost' belongs to the policy, not to an assignment",
                PolicyAssignmentValidator.rejectPolicyFieldsOn(MAPPER.readTree("{\"policy\": \"p\", \"cost\": {}}")));
        assertNull(PolicyAssignmentValidator.rejectPolicyFieldsOn(MAPPER.readTree("{\"policy\": \"p\", \"ticket\": \"T-1\"}")));
    }

    @Test
    void testSubjectOverlap() {
        PolicySubjectRule alice = rule("sub", PolicySubjectRule.Function.EQUAL, "alice");
        PolicySubjectRule aliceUpper = rule("sub", PolicySubjectRule.Function.EQUAL, "ALICE");
        PolicySubjectRule bob = rule("sub", PolicySubjectRule.Function.EQUAL, "bob");
        PolicySubjectRule everyone = rule("sub", PolicySubjectRule.Function.TRUE);
        PolicySubjectRule nobody = rule("sub", PolicySubjectRule.Function.FALSE);
        PolicySubjectRule gold = rule("roles", PolicySubjectRule.Function.EQUAL, "gold");
        PolicySubjectRule goldMember = rule("roles", PolicySubjectRule.Function.CONTAIN, "old");
        PolicySubjectRule silverMember = rule("roles", PolicySubjectRule.Function.CONTAIN, "silver");
        PolicySubjectRule tier = rule("roles", PolicySubjectRule.Function.REGEX, "gol.");
        PolicySubjectRule otherTier = rule("roles", PolicySubjectRule.Function.REGEX, "silv.");

        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(alice), List.of(aliceUpper)));
        assertFalse(PolicyAssignmentValidator.subjectsOverlap(List.of(alice), List.of(bob)));
        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(alice, bob), List.of(bob)));
        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(everyone), List.of(bob)));
        assertFalse(PolicyAssignmentValidator.subjectsOverlap(List.of(nobody), List.of(everyone)));
        // one caller can satisfy rules on two sources
        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(alice), List.of(gold)));
        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(gold), List.of(goldMember)));
        assertFalse(PolicyAssignmentValidator.subjectsOverlap(List.of(gold), List.of(silverMember)));
        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(goldMember), List.of(silverMember)));
        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(tier), List.of(gold)));
        assertFalse(PolicyAssignmentValidator.subjectsOverlap(List.of(otherTier), List.of(gold)));
        assertTrue(PolicyAssignmentValidator.subjectsOverlap(List.of(tier), List.of(tier)));
        assertFalse(PolicyAssignmentValidator.subjectsOverlap(List.of(tier), List.of(otherTier)));
        assertFalse(PolicyAssignmentValidator.subjectsOverlap(List.of(tier), List.of(silverMember)));
    }

    @Test
    void testObjectSetJson() throws Exception {
        assertEquals("\"*\"", MAPPER.writeValueAsString(PolicyObjectSet.all()));
        assertEquals("[\"opus\",\"sonnet\"]", MAPPER.writeValueAsString(PolicyObjectSet.of(List.of("sonnet", "opus"))));
        assertTrue(MAPPER.readValue("\"*\"", PolicyObjectSet.class).isAll());
        PolicyObjectSet listed = MAPPER.readValue("[\"opus\", \"sonnet\"]", PolicyObjectSet.class);
        assertFalse(listed.isAll());
        assertTrue(listed.contains("opus"));
        assertFalse(listed.contains("gpt"));
        assertThrows(Exception.class, () -> MAPPER.readValue("\"opus\"", PolicyObjectSet.class));
        assertThrows(Exception.class, () -> MAPPER.readValue("[]", PolicyObjectSet.class));
        assertThrows(Exception.class, () -> MAPPER.readValue("[\"*\"]", PolicyObjectSet.class));
        assertThrows(Exception.class, () -> MAPPER.readValue("[\"\"]", PolicyObjectSet.class));
        assertThrows(Exception.class, () -> MAPPER.readValue("7", PolicyObjectSet.class));
    }

    @Test
    void testObjectSetIntersection() {
        PolicyObjectSet all = PolicyObjectSet.all();
        PolicyObjectSet opus = PolicyObjectSet.of(List.of("opus"));
        PolicyObjectSet both = PolicyObjectSet.of(List.of("opus", "sonnet"));
        PolicyObjectSet gpt = PolicyObjectSet.of(List.of("gpt"));

        assertTrue(all.intersects(opus));
        assertTrue(opus.intersects(all));
        assertTrue(opus.intersects(both));
        assertFalse(opus.intersects(gpt));
    }
}
