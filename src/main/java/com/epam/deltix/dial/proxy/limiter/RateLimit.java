package com.epam.deltix.dial.proxy.limiter;

import com.epam.deltix.dial.proxy.config.Limit;

public class RateLimit {

    private final RateBucket minute = new RateBucket(RateWindow.MINUTE);
    private final RateBucket day = new RateBucket(RateWindow.DAY);

    public synchronized void add(long timestamp, long count) {
        minute.add(timestamp, count);
        day.add(timestamp, count);
    }

    public synchronized boolean update(long timestamp, Limit limit) {
        long minuteTotal = minute.update(timestamp);
        long dayTotal = day.update(timestamp);

        return minuteTotal >= limit.getMinute() || dayTotal >= limit.getDay();
    }
}