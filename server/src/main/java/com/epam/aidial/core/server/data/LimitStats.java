package com.epam.aidial.core.server.data;

import lombok.Data;

@Data
public class LimitStats {
    private ItemLimitStats minuteTokenStats;
    private ItemLimitStats dayTokenStats;
    private ItemLimitStats hourRequestStats;
    private ItemLimitStats dayRequestStats;
}
