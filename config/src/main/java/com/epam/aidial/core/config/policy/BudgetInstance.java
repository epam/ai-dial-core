package com.epam.aidial.core.config.policy;

import java.util.List;

/**
 * One budget that applies to a cost object: an assignment of a policy, or a self-applying policy.
 *
 * @param id         path of the assignment, or of the policy when it applies by itself
 * @param policyPath path of the policy
 * @param policy     the policy as stored at {@code policyPath}
 * @param assignment the assignment, or {@code null} for a self-applying policy
 * @param subject    the subject rules, pinned by the policy or by the assignment
 */
public record BudgetInstance(String id, String policyPath, Policy policy, PolicyAssignment assignment,
                             List<PolicySubjectRule> subject) {
}
