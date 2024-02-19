package com.epam.aidial.core.data;

import lombok.Data;

@Data
public class LimitStats {
    private TokenLimitStats minuteTokenStats;
    private TokenLimitStats dayTokenStats;
}
