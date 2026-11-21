package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.config.policy.BudgetInstance;

/**
 * The budget that pays for one call, picked by the pre-call check and charged after the call.
 */
public record BudgetPayer(String costObject, BudgetInstance instance, String userId) {
}
