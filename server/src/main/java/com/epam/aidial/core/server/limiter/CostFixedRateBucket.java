package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.concurrent.TimeUnit;

/**
 * {@link FixedRateBucket}'s BigDecimal analogue, for cost-based fixed calendar windows. See
 * {@link FixedRateBucket} for why {@code ignoreUnknown = true} is load-bearing for the rollout.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CostFixedRateBucket {

    private long periodStart = Long.MIN_VALUE;
    private BigDecimal count = BigDecimal.ZERO;

    public BigDecimal reconcile(long timestamp, CalendarPeriod period, RateLimitSchedule schedule) {
        long currentPeriodStart = CalendarWindowCalculator.currentPeriodStart(period, timestamp, schedule);
        if (currentPeriodStart != periodStart) {
            periodStart = currentPeriodStart;
            count = BigDecimal.ZERO;
        }
        return count;
    }

    public BigDecimal add(long timestamp, CalendarPeriod period, RateLimitSchedule schedule, BigDecimal amount) {
        reconcile(timestamp, period, schedule);
        count = count.add(amount);
        return count;
    }

    long retryAfterSeconds(long timestamp, CalendarPeriod period, RateLimitSchedule schedule) {
        long resetsAt = CalendarWindowCalculator.nextPeriodStart(period, timestamp, schedule);
        return TimeUnit.MILLISECONDS.toSeconds(resetsAt - timestamp);
    }

    long resetsAtMillis(long timestamp, CalendarPeriod period, RateLimitSchedule schedule) {
        return CalendarWindowCalculator.nextPeriodStart(period, timestamp, schedule);
    }
}
