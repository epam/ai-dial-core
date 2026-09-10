package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.TimeUnit;

/**
 * A fixed calendar-window counter: unlike {@link RateBucket}'s sliding sub-intervals, this only
 * ever needs "how much has happened in the period we're currently in" - a read recomputes what
 * {@code periodStart} should be for "now", and treats {@code count} as reset to zero if it doesn't
 * match the stored value.
 *
 * <p>{@code ignoreUnknown = true} is required, not cosmetic: it lets a pre-rollout record, still
 * shaped like the old floating-window {@link RateBucket} ({@code window}/{@code sums}/{@code sum}/
 * {@code start}/{@code end}), deserialize here instead of throwing. Its unrecognized fields are
 * dropped, {@code periodStart} is left at its sentinel default, and {@link #reconcile} then sees
 * "not a match" on first touch and zeroes {@code count} - the implicit one-time reset the rollout
 * relies on, without any explicit migration code.
 */
@Data
@NoArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class FixedRateBucket {

    private long periodStart = Long.MIN_VALUE;
    private long count;

    /**
     * Rolls the bucket over to the current calendar period if it has changed since the last touch,
     * and returns the (possibly just-reset) running total for that period.
     */
    public long reconcile(long timestamp, CalendarPeriod period, RateLimitSchedule schedule) {
        long currentPeriodStart = CalendarWindowCalculator.currentPeriodStart(period, timestamp, schedule);
        if (currentPeriodStart != periodStart) {
            periodStart = currentPeriodStart;
            count = 0;
        }
        return count;
    }

    public long add(long timestamp, CalendarPeriod period, RateLimitSchedule schedule, long amount) {
        reconcile(timestamp, period, schedule);
        count += amount;
        return count;
    }

    /**
     * Seconds until this window's next reset. Only meaningful when the caller already knows this
     * window is over its limit - unlike {@link RateBucket#retryAfter}, there is no "walk forward
     * and stop early" degenerate case that returns 0 when under limit.
     */
    long retryAfterSeconds(long timestamp, CalendarPeriod period, RateLimitSchedule schedule) {
        long resetsAt = CalendarWindowCalculator.nextPeriodStart(period, timestamp, schedule);
        return TimeUnit.MILLISECONDS.toSeconds(resetsAt - timestamp);
    }

    long resetsAtMillis(long timestamp, CalendarPeriod period, RateLimitSchedule schedule) {
        return CalendarWindowCalculator.nextPeriodStart(period, timestamp, schedule);
    }
}
