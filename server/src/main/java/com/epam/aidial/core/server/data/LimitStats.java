package com.epam.aidial.core.server.data;

import lombok.Data;

@Data
public class LimitStats {
    private ItemLimitStats minuteTokenStats;
    private ItemLimitStats dayTokenStats;
    private ItemLimitStats weekTokenStats;
    private ItemLimitStats monthTokenStats;
    private ItemLimitStats hourRequestStats;
    private ItemLimitStats dayRequestStats;
}
