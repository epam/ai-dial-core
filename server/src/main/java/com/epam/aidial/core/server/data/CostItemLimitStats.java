package com.epam.aidial.core.server.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.math.BigDecimal;

/**
 * Statistics for cost-based limits, using BigDecimal for precision.
 */
@Data
public class CostItemLimitStats {
    private BigDecimal total = BigDecimal.ZERO;
    private BigDecimal used = BigDecimal.ZERO;
    /**
     * Absolute instant this window's usage resets, as an ISO-8601 offset date-time - present only
     * for the fixed calendar windows (DAY/WEEK/MONTH), omitted for the floating ones (MINUTE/HOUR),
     * which have no single reset instant to report.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String resetsAt;
}
