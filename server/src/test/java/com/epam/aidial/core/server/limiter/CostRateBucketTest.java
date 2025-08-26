package com.epam.aidial.core.server.limiter;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Comprehensive unit tests for the CostRateBucket class.
 * - These tests verify:
 * - Constructor and initialization
 * - Adding costs and updating the window
 * - Window sliding behavior for different time windows (minute, day, week, month)
 * - Retry calculation
 * - Edge cases and precision handling with BigDecimal
 */
class CostRateBucketTest {

    private CostRateBucket bucket;

    @BeforeEach
    void setUp() {
        // Reset the bucket before each test
        bucket = null;
    }

    /**
     * Tests the constructor and initialization of CostRateBucket with different windows.
     * Verifies that the bucket is properly initialized with the correct window,
     * zero sum, and the correct number of intervals.
     */
    @Test
    void testConstructorAndInitialization() {
        // Test constructor with different windows
        bucket = new CostRateBucket(RateWindow.MINUTE);
        assertEquals(RateWindow.MINUTE, bucket.getWindow());
        assertEquals(BigDecimal.ZERO, bucket.getSum());
        assertEquals(60, bucket.getSums().length);

        bucket = new CostRateBucket(RateWindow.DAY);
        assertEquals(RateWindow.DAY, bucket.getWindow());
        assertEquals(BigDecimal.ZERO, bucket.getSum());
        assertEquals(24, bucket.getSums().length);

        bucket = new CostRateBucket(RateWindow.WEEK);
        assertEquals(RateWindow.WEEK, bucket.getWindow());
        assertEquals(BigDecimal.ZERO, bucket.getSum());
        assertEquals(7, bucket.getSums().length);

        bucket = new CostRateBucket(RateWindow.MONTH);
        assertEquals(RateWindow.MONTH, bucket.getWindow());
        assertEquals(BigDecimal.ZERO, bucket.getSum());
        assertEquals(30, bucket.getSums().length);
    }

    /**
     * Tests the minute window bucket behavior.
     * Verifies that costs are properly added and tracked within the minute window,
     * and that the window slides correctly as time advances, removing expired costs.
     */
    @Test
    void testMinuteBucket() {
        bucket = new CostRateBucket(RateWindow.MINUTE);

        update(0, "0.00");
        add(0, "0.10", "0.10");
        add(0, "0.20", "0.30");
        update(0, "0.30");

        add(1, "0.30", "0.60");
        add(59, "0.40", "1.00");
        update(59, "1.00");

        add(60, "0.10", "0.80");
        update(60, "0.80");

        add(61, "0.05", "0.55");
        update(61, "0.55");

        update(121, "0.00");
    }

    @Test
    void testDayBucket() {
        bucket = new CostRateBucket(RateWindow.DAY);

        update(0, "0.00");
        add(0, "0.10", "0.10");
        add(0, "0.20", "0.30");
        update(0, "0.30");

        add(1, "0.30", "0.60");
        add(23, "0.40", "1.00");
        update(23, "1.00");

        add(24, "0.10", "0.80");
        update(24, "0.80");

        add(25, "0.05", "0.55");
        update(25, "0.55");

        update(49, "0.00");
    }

    @Test
    void testWeekBucket() {
        bucket = new CostRateBucket(RateWindow.WEEK);

        update(0, "0.00");
        add(0, "0.10", "0.10");
        add(0, "0.20", "0.30");
        update(0, "0.30");

        add(1, "0.30", "0.60");
        add(6, "0.40", "1.00");
        update(6, "1.00");

        add(7, "0.10", "0.80");
        update(7, "0.80");

        add(8, "0.05", "0.55");
        update(8, "0.55");

        update(15, "0.00");
    }

    @Test
    void testMonthBucket() {
        bucket = new CostRateBucket(RateWindow.MONTH);

        update(0, "0.00");
        add(0, "0.10", "0.10");
        add(0, "0.20", "0.30");
        update(0, "0.30");

        add(1, "0.30", "0.60");
        add(29, "0.40", "1.00");
        update(29, "1.00");

        add(30, "0.10", "0.80");
        update(30, "0.80");

        add(31, "0.05", "0.55");
        update(31, "0.55");

        update(61, "0.00");
    }

    /**
     * Tests the retry calculation for the minute window.
     * Verifies that the retryAfter method correctly calculates how long to wait
     * before making a retry request when the cost limit is exceeded.
     * Also verifies that the retry time decreases as the window slides.
     */
    @Test
    void testRetryAfterMinute() {
        bucket = new CostRateBucket(RateWindow.MINUTE);

        update(0, "0.00");
        assertEquals(0, bucket.retryAfter(new BigDecimal("0.30")));
        add(0, "0.10", "0.10");

        update(5, "0.10");
        assertEquals(0, bucket.retryAfter(new BigDecimal("0.30")));
        add(5, "0.20", "0.30");

        update(15, "0.30");
        // When sum equals limit, retryAfter will return a non-zero value
        // because of the >= comparison in the method
        long retryTime1 = bucket.retryAfter(new BigDecimal("0.30"));
        assertTrue(retryTime1 > 0, "Retry time should be greater than 0 when sum equals limit");
        add(15, "0.30", "0.60");

        update(25, "0.60");
        long retryTime2 = bucket.retryAfter(new BigDecimal("0.30"));
        assertTrue(retryTime2 > 0, "Retry time should be greater than 0 when sum exceeds limit");
        add(25, "0.10", "0.70");

        update(60, "0.60");
        long retryTime3 = bucket.retryAfter(new BigDecimal("0.30"));
        assertTrue(retryTime3 > 0, "Retry time should be greater than 0 when sum exceeds limit");
        assertTrue(retryTime3 < retryTime2, "Retry time should decrease after window slides");
    }

    /**
     * Tests the retry calculation for the day window.
     * Verifies that the retryAfter method correctly calculates how long to wait
     * before making a retry request when the cost limit is exceeded with a day window.
     * Also verifies that the retry time decreases as the window slides.
     */
    @Test
    void testRetryAfterDay() {
        bucket = new CostRateBucket(RateWindow.DAY);

        update(0, "0.00");
        assertEquals(0, bucket.retryAfter(new BigDecimal("0.30")));
        add(0, "0.10", "0.10");

        update(5, "0.10");
        assertEquals(0, bucket.retryAfter(new BigDecimal("0.30")));
        add(5, "0.20", "0.30");

        update(10, "0.30");
        // When sum equals limit, retryAfter will return a non-zero value
        // because of the >= comparison in the method
        long retryTime1 = bucket.retryAfter(new BigDecimal("0.30"));
        assertTrue(retryTime1 > 0, "Retry time should be greater than 0 when sum equals limit");
        add(10, "0.30", "0.60");

        update(20, "0.60");
        long retryTime2 = bucket.retryAfter(new BigDecimal("0.30"));
        assertTrue(retryTime2 > 0, "Retry time should be greater than 0 when sum exceeds limit");
        add(23, "0.10", "0.70");

        update(24, "0.60");
        long retryTime3 = bucket.retryAfter(new BigDecimal("0.30"));
        assertTrue(retryTime3 > 0, "Retry time should be greater than 0 when sum exceeds limit");
        assertTrue(retryTime3 < retryTime2, "Retry time should decrease after window slides");
    }

    /**
     * Tests edge cases for the CostRateBucket.
     * Verifies behavior with:
     * - Timestamps less than window size (should throw IllegalArgumentException)
     * - Very large costs
     * - Very small costs
     * - Zero costs
     * - Negative costs (for robustness testing)
     */
    @Test
    void testEdgeCases() {
        bucket = new CostRateBucket(RateWindow.MINUTE);

        // Test with timestamp less than window
        assertThrows(IllegalArgumentException.class, () -> bucket.add(0, BigDecimal.ONE));
        assertThrows(IllegalArgumentException.class, () -> bucket.update(0));

        // Test with very large costs
        update(0, "0.00");
        add(0, "1000000.00", "1000000.00");
        assertEquals(new BigDecimal("1000000.00"), bucket.getSum());

        // Test with very small costs
        bucket = new CostRateBucket(RateWindow.MINUTE);
        update(0, "0.00");
        add(0, "0.0000001", "0.0000001");
        assertEquals(new BigDecimal("0.0000001"), bucket.getSum());

        // Test with zero cost
        add(1, "0.00", "0.0000001");
        assertEquals(new BigDecimal("0.0000001"), bucket.getSum());

        // Test with negative cost (should not be used in practice, but testing for robustness)
        add(2, "-0.0000001", "0.0000000");
        assertEquals(new BigDecimal("0.0000000"), bucket.getSum());
    }

    /**
     * Tests precision handling with BigDecimal values.
     * Verifies that the CostRateBucket correctly maintains precision
     * when adding costs with different decimal places.
     * This is important for accurate cost tracking and calculations.
     */
    @Test
    void testPrecisionHandling() {
        bucket = new CostRateBucket(RateWindow.MINUTE);

        update(0, "0.00");
        add(0, "0.1", "0.1");
        add(0, "0.01", "0.11");
        add(0, "0.001", "0.111");
        add(0, "0.0001", "0.1111");

        // Verify that precision is maintained
        assertEquals(new BigDecimal("0.1111"), bucket.getSum());

        // Test with different decimal places
        add(1, "0.12345", "0.23455");
        assertEquals(new BigDecimal("0.23455"), bucket.getSum());
    }

    /**
     * Helper method to add a cost at a specific interval and verify the expected sum.
     *
     * @param interval The interval number (0 = first interval, 1 = second interval, etc.)
     * @param cost The cost to add as a string (will be converted to BigDecimal)
     * @param expected The expected sum after adding the cost
     */
    private void add(long interval, String cost, String expected) {
        RateWindow window = bucket.getWindow();
        long whole = interval * window.interval();
        long fraction = ThreadLocalRandom.current().nextLong(0, window.interval());

        long timestamp = window.window() + whole + fraction;
        BigDecimal actual = bucket.add(timestamp, new BigDecimal(cost));
        assertEquals(0, new BigDecimal(expected).compareTo(actual), 
                "Expected: " + expected + ", Actual: " + actual);
    }

    /**
     * Helper method to update the bucket at a specific interval and verify the expected sum.
     *
     * @param interval The interval number (0 = first interval, 1 = second interval, etc.)
     * @param expected The expected sum after updating the bucket
     */
    private void update(long interval, String expected) {
        RateWindow window = bucket.getWindow();
        long whole = interval * window.interval();
        long fraction = ThreadLocalRandom.current().nextLong(0, window.interval());

        long timestamp = window.window() + whole + fraction;
        BigDecimal actual = bucket.update(timestamp);
        assertEquals(0, new BigDecimal(expected).compareTo(actual), 
                "Expected: " + expected + ", Actual: " + actual);
    }
}