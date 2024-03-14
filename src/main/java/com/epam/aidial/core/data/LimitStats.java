package com.epam.aidial.core.data;

import lombok.Data;

@Data
public class LimitStats {
    private ItemLimitStats minuteTokenStats;
    private ItemLimitStats dayTokenStats;
    private ItemLimitStats hourRequestStats;
    private ItemLimitStats dayRequestStats;
}
