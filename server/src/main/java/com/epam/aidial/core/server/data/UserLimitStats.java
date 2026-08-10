package com.epam.aidial.core.server.data;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Limits and rolling usage for every deployment available to the authenticated caller.
 *
 * <p>Cost stats sit at the top level rather than on each deployment because DIAL tracks cost per
 * caller across all models - both the limit ({@code Role.costLimit}) and the counter
 * ({@code limits/costs}) are deployment-agnostic - so there is no per-deployment cost to report.
 */
@Data
public class UserLimitStats {
    private List<DeploymentLimitStats> deployments = new ArrayList<>();
    private CostItemLimitStats minuteCostStats;
    private CostItemLimitStats dayCostStats;
    private CostItemLimitStats weekCostStats;
    private CostItemLimitStats monthCostStats;
}
