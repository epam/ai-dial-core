package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.CostLimit;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.apache.commons.lang3.math.NumberUtils;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Rate limiter for cost-based limits, using BigDecimal for precision.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class CostRateLimit {

    private static final NumberFormat CURRENCY_FORMAT = NumberFormat.getCurrencyInstance(Locale.US);

    private final CostRateBucket minute = new CostRateBucket(RateWindow.MINUTE);
    private final CostRateBucket day = new CostRateBucket(RateWindow.DAY);
    private final CostRateBucket week = new CostRateBucket(RateWindow.WEEK);
    private final CostRateBucket month = new CostRateBucket(RateWindow.MONTH);

    /**
     * Adds cost usage to all buckets.
     *
     * @param timestamp The current timestamp
     * @param cost The cost to add
     */
    public void add(long timestamp, BigDecimal cost) {
        if (cost == null || cost.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        
        minute.add(timestamp, cost);
        day.add(timestamp, cost);
        week.add(timestamp, cost);
        month.add(timestamp, cost);
    }

    /**
     * Checks if any cost limit is exceeded.
     *
     * @param timestamp The current timestamp
     * @param costLimit The cost limits to check against
     * @return A RateLimitResult indicating success or failure
     */
    public RateLimitResult check(long timestamp, CostLimit costLimit) {
        BigDecimal minuteTotal = minute.update(timestamp);
        BigDecimal dayTotal = day.update(timestamp);
        BigDecimal weekTotal = week.update(timestamp);
        BigDecimal monthTotal = month.update(timestamp);

        boolean result = minuteTotal.compareTo(costLimit.getMinute()) >= 0 
                || dayTotal.compareTo(costLimit.getDay()) >= 0
                || weekTotal.compareTo(costLimit.getWeek()) >= 0 
                || monthTotal.compareTo(costLimit.getMonth()) >= 0;
                
        if (result) {
            String errorMsg = String.format(
                    "Hit cost rate limit. Minute limit: %s / %s. Day limit: %s / %s. Week limit: %s / %s. Month limit: %s / %s.",
                    format(minuteTotal), format(costLimit.getMinute()), format(dayTotal), format(costLimit.getDay()),
                    format(weekTotal), format(costLimit.getWeek()), format(monthTotal), format(costLimit.getMonth()));
                    
            long minuteRetryAfter = minute.retryAfter(costLimit.getMinute());
            long dayRetryAfter = day.retryAfter(costLimit.getDay());
            long weekRetryAfter = week.retryAfter(costLimit.getWeek());
            long monthRetryAfter = month.retryAfter(costLimit.getMonth());
            
            long retryAfter = NumberUtils.max(minuteRetryAfter, dayRetryAfter, weekRetryAfter, monthRetryAfter);
            
            List<String> limits = new ArrayList<>();
            StringBuilder displayError = new StringBuilder("You've exceeded your");
            
            if (monthTotal.compareTo(costLimit.getMonth()) >= 0) {
                limits.add("monthly");
            }
            if (weekTotal.compareTo(costLimit.getWeek()) >= 0) {
                limits.add("weekly");
            }
            if (dayTotal.compareTo(costLimit.getDay()) >= 0) {
                limits.add("daily");
            }
            if (minuteTotal.compareTo(costLimit.getMinute()) >= 0) {
                limits.add("minutely");
            }
            
            for (int i = 0; i < limits.size(); i++) {
                if (i > 0) {
                    if (i == limits.size() - 1) {
                        displayError.append(" and ");
                    } else {
                        displayError.append(',');
                    }
                }
                displayError.append(' ');
                displayError.append(limits.get(i));
            }
            
            displayError.append(" cost limit");
            if (limits.size() > 1) {
                displayError.append('s');
            }
            
            return new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, errorMsg, displayError.toString(), retryAfter);
        } else {
            return RateLimitResult.SUCCESS;
        }
    }

    private static String format(BigDecimal n) {
        return CURRENCY_FORMAT.format(n);
    }

    /**
     * Updates the limit statistics with the current usage.
     *
     * @param timestamp The current timestamp
     * @param limitStats The limit statistics to update
     */
    public void update(long timestamp, LimitStats limitStats) {
        BigDecimal minuteTotal = minute.update(timestamp);
        BigDecimal dayTotal = day.update(timestamp);
        BigDecimal weekTotal = week.update(timestamp);
        BigDecimal monthTotal = month.update(timestamp);
        
        limitStats.getMinuteCostStats().setUsed(minuteTotal);
        limitStats.getDayCostStats().setUsed(dayTotal);
        limitStats.getWeekCostStats().setUsed(weekTotal);
        limitStats.getMonthCostStats().setUsed(monthTotal);
    }
}