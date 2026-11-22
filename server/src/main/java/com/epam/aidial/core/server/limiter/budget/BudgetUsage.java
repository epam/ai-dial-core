package com.epam.aidial.core.server.limiter.budget;

import java.math.BigDecimal;

/**
 * @param dayResetsAt   epoch millis at which the current day window ends
 * @param monthResetsAt epoch millis at which the current month window ends
 */
public record BudgetUsage(BigDecimal day, BigDecimal month, long dayResetsAt, long monthResetsAt) {
}
