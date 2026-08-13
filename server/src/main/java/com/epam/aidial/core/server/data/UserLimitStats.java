package com.epam.aidial.core.server.data;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Limits and rolling usage for the deployments of the authenticated caller, keyed by deployment id.
 *
 * <p>The top-level cost stats are the caller's money budget and the spend against it: {@code Role.costLimit}
 * has no deployment key and usage accumulates in a single {@code limits/costs} document, so the budget cannot
 * be scoped to a deployment. A per-deployment {@code *CostStats} is that deployment's attributed spend against
 * no cap, so its {@code total} carries the unlimited sentinel.
 */
@Data
public class UserLimitStats {
    private Map<String, LimitStats> deployments = new LinkedHashMap<>();
    private CostItemLimitStats minuteCostStats;
    private CostItemLimitStats dayCostStats;
    private CostItemLimitStats weekCostStats;
    private CostItemLimitStats monthCostStats;
}
