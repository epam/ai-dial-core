package com.epam.aidial.core.data;

import lombok.Data;

@Data
public class TokenLimitStats {
    private long total;
    private long used;
}
