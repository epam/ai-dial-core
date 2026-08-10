package com.epam.aidial.core.server.data;

import lombok.Data;

/**
 * Per-deployment slice of {@link LimitStats}. Cost is absent by design: it is aggregated per caller
 * across all models, so it is reported once on {@link UserLimitStats} instead.
 */
@Data
public class DeploymentLimitStats {
    private String id;
    private ItemLimitStats minuteTokenStats;
    private ItemLimitStats dayTokenStats;
    private ItemLimitStats weekTokenStats;
    private ItemLimitStats monthTokenStats;
    private ItemLimitStats hourRequestStats;
    private ItemLimitStats dayRequestStats;
}
