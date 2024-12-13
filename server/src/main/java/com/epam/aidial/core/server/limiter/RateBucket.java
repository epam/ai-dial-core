package com.epam.aidial.core.server.limiter;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.TimeUnit;

@Data
@NoArgsConstructor
public class RateBucket {

    private RateWindow window;
    private long[] sums;
    private long sum;
    private long start = Long.MIN_VALUE;
    private long end = Long.MIN_VALUE;

    public RateBucket(RateWindow window) {
        this.window = window;
        this.sums = new long[window.intervals()];
    }

    public long add(long timestamp, long count) {
        update(timestamp);

        long interval = interval(timestamp);
        int index = index(interval);

        sums[index] += count;
        sum += count;
        return sum;
    }

    public long update(long timestamp) {
        long interval = interval(timestamp);

        if (interval >= end) {
            long newEnd = interval + 1;
            long newStart = newEnd - window.intervals();

            long cleanStart = start;
            long cleanEnd = Math.min(end, newStart);

            for (; cleanStart < cleanEnd; cleanStart++) {
                int index = index(cleanStart);
                sum -= sums[index];
                sums[index] = 0;
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
    long retryAfter(long limit) {
        long sum = this.sum;
        long replyAfter = 0;
        for (long start = this.start;  start < this.end && sum >= limit; start++) {
            int index = index(start);
            sum -= sums[index];
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