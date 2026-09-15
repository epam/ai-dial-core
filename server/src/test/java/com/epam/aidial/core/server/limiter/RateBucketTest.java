package com.epam.aidial.core.server.limiter;

import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RateBucketTest {

    private RateBucket bucket;

    @Test
    void testMinuteBucket() {
        bucket = new RateBucket(RateWindow.MINUTE);

        update(0, 0);
        add(0, 10, 10);
        add(0, 20, 30);
        update(0, 30);

        add(1, 30, 60);
        add(59, 40, 100);
        update(59, 100);

        add(60, 10, 80);
        update(60, 80);

        add(61, 5, 55);
        update(61, 55);

        update(121, 0);
    }

    @Test
    public void testRetryAfterMinute() {
        bucket = new RateBucket(RateWindow.MINUTE);

        update(0, 0);
        assertEquals(0, bucket.retryAfter(30));
        add(0, 10, 10);

        update(5, 10);
        assertEquals(0, bucket.retryAfter(30));
        add(5, 20, 30);

        update(15, 30);
        assertEquals(45, bucket.retryAfter(30));
        add(15, 30, 60);

        update(25, 60);
        add(25, 10, 70);

        update(60, 60);
        assertEquals(15, bucket.retryAfter(30));
    }

    private void add(long interval, long count, long expected) {
        RateWindow window = bucket.getWindow();
        long whole = interval * window.interval();
        long fraction = ThreadLocalRandom.current().nextLong(0, window.interval());

        long timestamp = window.window() + whole + fraction;
        long actual = bucket.add(timestamp, count);
        assertEquals(expected, actual);
    }

    private void update(long interval, long expected) {
        RateWindow window = bucket.getWindow();
        long whole = interval * window.interval();
        long fraction = ThreadLocalRandom.current().nextLong(0, window.interval());

        long timestamp = window.window() + whole + fraction;
        long actual = bucket.update(timestamp);
        assertEquals(expected, actual);
    }
}
