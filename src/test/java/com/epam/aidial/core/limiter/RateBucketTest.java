package com.epam.aidial.core.limiter;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ThreadLocalRandom;

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
    void testDayBucket() {
        bucket = new RateBucket(RateWindow.DAY);

        update(0, 0);
        add(0, 10, 10);
        add(0, 20, 30);
        update(0, 30);

        add(1, 30, 60);
        add(23, 40, 100);
        update(23, 100);

        add(24, 10, 80);
        update(24, 80);

        add(25, 5, 55);
        update(25, 55);

        update(49, 0);
    }

    private void add(long interval, long count, long expected) {
        RateWindow window = bucket.getWindow();
        long whole = interval * window.interval();
        long fraction = ThreadLocalRandom.current().nextLong(0, window.interval());

        long timestamp = window.window() + whole + fraction;
        long actual = bucket.add(timestamp, count);
        Assertions.assertEquals(expected, actual);
    }

    private void update(long interval, long expected) {
        RateWindow window = bucket.getWindow();
        long whole = interval * window.interval();
        long fraction = ThreadLocalRandom.current().nextLong(0, window.interval());

        long timestamp = window.window() + whole + fraction;
        long actual = bucket.update(timestamp);
        Assertions.assertEquals(expected, actual);
    }
}
