package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.policy.BudgetInstance;
import com.epam.aidial.core.config.policy.CostObjectIndex;
import com.epam.aidial.core.config.policy.PolicyKind;
import com.epam.aidial.core.config.policy.PolicySubjectRule;
import com.epam.aidial.core.server.data.Rule;
import com.epam.aidial.core.server.security.ExtractedClaims;
import com.epam.aidial.core.server.security.RuleMatcher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Answers which budgets can pay for a call. Pure and in memory: the order is a total order, so every pod
 * returns the same list for the same index.
 */
@Slf4j
@UtilityClass
public class EffectiveBudgets {

    private static final Comparator<BudgetInstance> ORDER = Comparator
            .comparing((BudgetInstance instance) -> instance.policy().getPriority(), Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(BudgetInstance::id);

    private static final Counter AMBIGUOUS_PRIORITY = Counter.builder("dial_budget_ambiguous_priority_total")
            .description("Calls where budgets of different policies tie on priority")
            .register(Metrics.globalRegistry);

    /**
     * @param claims claims of the root initiator, {@code null} for an API key caller, which no budget reaches
     * @return the budgets that can pay, by policy priority (lower first) and then by id
     */
    public List<BudgetInstance> resolve(CostObjectIndex index, String costObject, ExtractedClaims claims, String model, Instant now) {
        if (claims == null) {
            return List.of();
        }
        List<BudgetInstance> candidates = index.getInstances(costObject).stream()
                .filter(instance -> instance.policy().getKind() == PolicyKind.BUDGET)
                .filter(instance -> !isExpired(instance, now))
                .filter(instance -> instance.policy().getObject().contains(model))
                .filter(instance -> isSubjectMatched(instance, claims))
                .sorted(ORDER)
                .toList();
        meterAmbiguousPriority(candidates, costObject);
        return candidates;
    }

    private boolean isExpired(BudgetInstance instance, Instant now) {
        if (instance.assignment() == null || instance.assignment().getExpiresAt() == null) {
            return false;
        }
        return !OffsetDateTime.parse(instance.assignment().getExpiresAt()).toInstant().isAfter(now);
    }

    private boolean isSubjectMatched(BudgetInstance instance, ExtractedClaims claims) {
        List<PolicySubjectRule> subject = instance.subject();
        if (subject == null) {
            return false;
        }
        List<Rule> rules = subject.stream().map(EffectiveBudgets::toRule).toList();
        return RuleMatcher.match(claims.userRoles(), claims.userClaims(), rules);
    }

    private Rule toRule(PolicySubjectRule subjectRule) {
        Rule rule = new Rule();
        rule.setSource(subjectRule.getSource());
        rule.setFunction(Rule.Function.valueOf(subjectRule.getFunction().name()));
        rule.setTargets(subjectRule.getTargets());
        return rule;
    }

    private void meterAmbiguousPriority(List<BudgetInstance> candidates, String costObject) {
        Map<Integer, Set<String>> policyPathsByPriority = new HashMap<>();
        for (BudgetInstance instance : candidates) {
            Integer priority = instance.policy().getPriority();
            if (priority != null) {
                policyPathsByPriority.computeIfAbsent(priority, key -> new HashSet<>()).add(instance.policyPath());
            }
        }
        policyPathsByPriority.forEach((priority, policyPaths) -> {
            if (policyPaths.size() > 1) {
                log.warn("Budgets of different policies share a priority: costObject={}, priority={}, policies={}",
                        costObject, priority, policyPaths);
                AMBIGUOUS_PRIORITY.increment();
            }
        });
    }
}
