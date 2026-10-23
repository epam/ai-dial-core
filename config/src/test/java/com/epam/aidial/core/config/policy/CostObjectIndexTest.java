package com.epam.aidial.core.config.policy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostObjectIndexTest {

    private static final List<PolicySubjectRule> EVERYONE = List.of(rule("TRUE"));
    private static final List<PolicySubjectRule> ALICE = List.of(rule("EQUAL"));

    private static PolicySubjectRule rule(String function) {
        PolicySubjectRule rule = new PolicySubjectRule();
        rule.setSource("sub");
        rule.setFunction(PolicySubjectRule.Function.valueOf(function));
        return rule;
    }

    private static Policy policy(List<PolicySubjectRule> subject, String costObject) {
        Policy policy = new Policy();
        policy.setSubject(subject);
        policy.setCostObject(costObject);
        return policy;
    }

    private static PolicyAssignment assignment(String policy, List<PolicySubjectRule> subject, String costObject) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicy(policy);
        assignment.setSubject(subject);
        assignment.setCostObject(costObject);
        return assignment;
    }

    @Test
    void testAssignmentOfGlobalPolicyIsIndexedUnderItsFolder() {
        Policy global = policy(null, null);
        PolicyAssignment grant = assignment("grant", ALICE, "IMF");

        CostObjectIndex index = CostObjectIndex.build(Map.of("grant", global), Map.of("IMF/a-1", grant));

        List<BudgetInstance> instances = index.getInstances("IMF");
        assertEquals(1, instances.size());
        assertEquals("IMF/a-1", instances.get(0).id());
        assertEquals("grant", instances.get(0).policyPath());
        assertSame(global, instances.get(0).policy());
        assertSame(grant, instances.get(0).assignment());
        assertSame(ALICE, instances.get(0).subject());
    }

    @Test
    void testSubjectPinnedByPolicyWinsOverAssignment() {
        CostObjectIndex index = CostObjectIndex.build(Map.of("p", policy(EVERYONE, null)),
                Map.of("IMF/a-1", assignment("p", null, "IMF")));

        assertSame(EVERYONE, index.getInstances("IMF").get(0).subject());
    }

    @Test
    void testSelfApplyingPolicyIsAnInstanceOfItsFolder() {
        Policy selfApplying = policy(EVERYONE, "IMF");

        CostObjectIndex index = CostObjectIndex.build(Map.of("IMF/default", selfApplying), Map.of());

        List<BudgetInstance> instances = index.getInstances("IMF");
        assertEquals(1, instances.size());
        assertEquals("IMF/default", instances.get(0).id());
        assertEquals("IMF/default", instances.get(0).policyPath());
        assertNull(instances.get(0).assignment());
    }

    @Test
    void testEqualIdsInDifferentFoldersCoexist() {
        Policy global = policy(null, null);

        CostObjectIndex index = CostObjectIndex.build(Map.of("grant", global), Map.of(
                "IMF/a-1", assignment("grant", ALICE, "IMF"),
                "ORG/a-1", assignment("grant", ALICE, "ORG")));

        assertEquals("IMF/a-1", index.getInstances("IMF").get(0).id());
        assertEquals("ORG/a-1", index.getInstances("ORG").get(0).id());
    }

    @Test
    void testGlobalAndProjectPolicyOfOneCostObjectResolveSeparately() {
        Policy global = policy(null, null);
        Policy project = policy(null, null);

        CostObjectIndex index = CostObjectIndex.build(Map.of("grant", global, "IMF/grant", project), Map.of(
                "IMF/a-1", assignment("grant", ALICE, "IMF"),
                "IMF/a-2", assignment("IMF/grant", ALICE, "IMF")));

        Map<String, Policy> policyById = Map.of(
                index.getInstances("IMF").get(0).id(), index.getInstances("IMF").get(0).policy(),
                index.getInstances("IMF").get(1).id(), index.getInstances("IMF").get(1).policy());
        assertSame(global, policyById.get("IMF/a-1"));
        assertSame(project, policyById.get("IMF/a-2"));
    }

    @Test
    void testAssignmentOfMissingPolicyIsNotAnInstance() {
        CostObjectIndex index = CostObjectIndex.build(Map.of(), Map.of("IMF/a-1", assignment("gone", ALICE, "IMF")));

        assertTrue(index.getInstances("IMF").isEmpty());
    }

    @Test
    void testSelfApplyingPolicyWithoutFolderIsNotAnInstance() {
        CostObjectIndex index = CostObjectIndex.build(Map.of("loose", policy(EVERYONE, "IMF")), Map.of());

        assertTrue(index.getInstances("IMF").isEmpty());
    }

    @Test
    void testUnknownCostObjectHasNoInstances() {
        assertTrue(CostObjectIndex.EMPTY.getInstances("IMF").isEmpty());
    }
}
