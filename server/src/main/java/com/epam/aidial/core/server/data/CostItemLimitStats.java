package com.epam.aidial.core.server.data;

import lombok.Data;

import java.math.BigDecimal;

/**
 * Statistics for cost-based limits, using BigDecimal for precision.
 */
@Data
public class CostItemLimitStats {
    private BigDecimal total;
    private BigDecimal used;
}