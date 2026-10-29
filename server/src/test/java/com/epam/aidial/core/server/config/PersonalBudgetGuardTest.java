package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.SpendingLimitMode;
import com.epam.aidial.core.config.policy.CostObjectIndex;
import com.epam.aidial.core.config.policy.Policy;
import com.epam.aidial.core.config.policy.PolicyAssignment;
import com.epam.aidial.core.config.policy.PolicyKind;
import com.epam.aidial.core.config.policy.PolicyQuantity;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalBudgetGuardTest {

    @Test
    void testOffModeNeverWarns() {
        assertFalse(MergedConfigStore.isPersonalBudgetMissing(config(SpendingLimitMode.OFF, Map.of(), Map.of())));
    }

    @Test
    void testShadowAndEnforceWithoutPersonalBudgetWarn() {
        assertTrue(MergedConfigStore.isPersonalBudgetMissing(config(SpendingLimitMode.SHADOW, Map.of(), Map.of())));
        assertTrue(MergedConfigStore.isPersonalBudgetMissing(config(SpendingLimitMode.ENFORCE, Map.of(), Map.of())));
    }

    @Test
    void testBudgetOfAnotherCostObjectDoesNotCount() {
        Config config = config(SpendingLimitMode.ENFORCE, Map.of("tier", costBudget()), Map.of("projA/g1", assignment(null)));

        assertTrue(MergedConfigStore.isPersonalBudgetMissing(config));
    }

    @Test
    void testPersonalBudgetSilencesTheGuard() {
        Config config = config(SpendingLimitMode.ENFORCE, Map.of("tier", costBudget()), Map.of("ORG/g1", assignment(null)));

        assertFalse(MergedConfigStore.isPersonalBudgetMissing(config));
    }

    @Test
    void testExpiredPersonalBudgetDoesNotCount() {
        String expired = Instant.now().minusSeconds(60).toString();
        Config config = config(SpendingLimitMode.SHADOW, Map.of("tier", costBudget()), Map.of("ORG/g1", assignment(expired)));

        assertTrue(MergedConfigStore.isPersonalBudgetMissing(config));
    }

    @Test
    void testBudgetOfAnotherQuantityDoesNotCount() {
        Policy tokens = costBudget();
        tokens.setQuantity(PolicyQuantity.TOKENS);
        Config config = config(SpendingLimitMode.SHADOW, Map.of("tier", tokens), Map.of("ORG/g1", assignment(null)));

        assertTrue(MergedConfigStore.isPersonalBudgetMissing(config));
    }

    private static Config config(SpendingLimitMode mode, Map<String, Policy> policies, Map<String, PolicyAssignment> assignments) {
        Config config = new Config();
        config.getSpendingLimits().setMode(mode);
        config.setCostObjectIndex(CostObjectIndex.build(policies, assignments));
        return config;
    }

    private static Policy costBudget() {
        Policy policy = new Policy();
        policy.setKind(PolicyKind.BUDGET);
        policy.setQuantity(PolicyQuantity.COST);
        policy.setSubject(List.of(new PolicySubjectRule()));
        return policy;
    }

    private static PolicyAssignment assignment(String expiresAt) {
        PolicyAssignment assignment = new PolicyAssignment();
        assignment.setPolicy("tier");
        assignment.setExpiresAt(expiresAt);
        return assignment;
    }
}
