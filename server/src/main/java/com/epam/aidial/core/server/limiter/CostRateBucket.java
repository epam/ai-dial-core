package com.epam.aidial.core.server.limiter;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * A rate bucket for cost-based rate limiting, using BigDecimal for precision.
 */
@Data
@NoArgsConstructor
public class CostRateBucket {

    private RateWindow window;
    private BigDecimal[] sums;
    private BigDecimal sum = BigDecimal.ZERO;
    private long start = Long.MIN_VALUE;
    private long end = Long.MIN_VALUE;

    public CostRateBucket(RateWindow window) {
        this.window = window;
        this.sums = new BigDecimal[window.intervals()];
        Arrays.fill(sums, BigDecimal.ZERO);
    }

    public BigDecimal add(long timestamp, BigDecimal cost) {
        update(timestamp);

        long interval = interval(timestamp);
        int index = index(interval);

        sums[index] = sums[index].add(cost);
        sum = sum.add(cost);

        return sum;
    }

    public BigDecimal update(long timestamp) {
        long interval = interval(timestamp);

        if (interval >= end) {
            long newEnd = interval + 1;
            long newStart = newEnd - window.intervals();

            long cleanStart = start;
            long cleanEnd = Math.min(end, newStart);

            for (; cleanStart < cleanEnd; cleanStart++) {
                int index = index(cleanStart);
                sum = sum.subtract(sums[index]);
                sums[index] = BigDecimal.ZERO;
            }

            start = newStart;
            end = newEnd;
        }

        return sum;
    }

    /**
     * Returns the number of seconds the user agent should wait before making a retry request.
     *
     * @param limit - requested limit
     */
    long retryAfter(BigDecimal limit) {
        BigDecimal currentSum = sum;
        long replyAfter = 0;
        for (long start = this.start; start < this.end && currentSum.compareTo(limit) >= 0; start++) {
            int index = index(start);
            currentSum = currentSum.subtract(sums[index]);
            replyAfter += window.interval();
        }
        return TimeUnit.MILLISECONDS.toSeconds(replyAfter);
    }

    private long interval(long timestamp) {
        if (timestamp < window.window()) {
            throw new IllegalArgumentException("timestamp < window");
        }

        return Math.max(timestamp / window.interval(), start);
    }

    private int index(long point) {
        return (int) (point % window.intervals());
    }
}