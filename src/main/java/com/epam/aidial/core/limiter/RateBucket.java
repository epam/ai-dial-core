package com.epam.aidial.core.limiter;

import lombok.Data;
import lombok.NoArgsConstructor;

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