package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.WeekDay;
import com.epam.aidial.core.server.util.ProxyUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CostFixedRateBucketTest {

    private final RateLimitSchedule schedule = utcMidnightSchedule();

    private static RateLimitSchedule utcMidnightSchedule() {
        RateLimitSchedule schedule = new RateLimitSchedule();
        schedule.setTimezone("UTC");
        schedule.setWeekStartDay(WeekDay.Mon);
        schedule.setResetTime("00:00");
        return schedule;
    }

    private static long instant(ZonedDateTime dateTime) {
        return dateTime.toInstant().toEpochMilli();
    }

    @Test
    void addAccumulatesWithinTheSamePeriod() {
        CostFixedRateBucket bucket = new CostFixedRateBucket();
        long day1 = instant(ZonedDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneId.of("UTC")));
        long day1Later = instant(ZonedDateTime.of(2026, 9, 9, 20, 0, 0, 0, ZoneId.of("UTC")));

        assertEquals(0, new BigDecimal("0.10").compareTo(bucket.add(day1, CalendarPeriod.DAY, schedule, new BigDecimal("0.10"))));
        assertEquals(0, new BigDecimal("0.30").compareTo(bucket.add(day1Later, CalendarPeriod.DAY, schedule, new BigDecimal("0.20"))));
    }

    @Test
    void reconcileResetsCountOnPeriodRollover() {
        CostFixedRateBucket bucket = new CostFixedRateBucket();
        long day1 = instant(ZonedDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneId.of("UTC")));
        long day2 = instant(ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, ZoneId.of("UTC")));

        bucket.add(day1, CalendarPeriod.DAY, schedule, new BigDecimal("0.50"));
        assertEquals(0, BigDecimal.ZERO.compareTo(bucket.reconcile(day2, CalendarPeriod.DAY, schedule)));
    }

    @Test
    void oldFloatingWindowJsonDeserializesWithoutThrowing() {
        String oldJson = "{\"window\":\"DAY\",\"sums\":[\"0.1\",\"0.2\"],\"sum\":\"0.3\",\"start\":100,\"end\":124}";

        CostFixedRateBucket bucket = ProxyUtil.convertToObject(oldJson, CostFixedRateBucket.class);

        assertTrue(bucket != null);
        assertEquals(0, BigDecimal.ZERO.compareTo(bucket.getCount()));
        long now = instant(ZonedDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneId.of("UTC")));
        assertEquals(0, BigDecimal.ZERO.compareTo(bucket.reconcile(now, CalendarPeriod.DAY, schedule)));
    }
}
