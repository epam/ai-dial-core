package com.epam.aidial.core.server.limiter.budget;

import com.epam.aidial.core.server.limiter.CostFixedRateBucket;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/**
 * What one user has spent in one model against one budget instance, per calendar window.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class BudgetCounter {

    private CostFixedRateBucket day = new CostFixedRateBucket();
    private CostFixedRateBucket month = new CostFixedRateBucket();
}
