package com.epam.aidial.core.server.data;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Statistics for cost-based limits, using BigDecimal for precision.
 */
@Data
public class CostItemLimitStats {
    private BigDecimal total = BigDecimal.ZERO;
    private BigDecimal used = BigDecimal.ZERO;
}