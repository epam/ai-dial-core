package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.WeekDay;
import com.epam.aidial.core.server.util.ProxyUtil;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FixedRateBucketTest {

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
        FixedRateBucket bucket = new FixedRateBucket();
        long day1 = instant(ZonedDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneId.of("UTC")));
        long day1Later = instant(ZonedDateTime.of(2026, 9, 9, 20, 0, 0, 0, ZoneId.of("UTC")));

        assertEquals(10, bucket.add(day1, CalendarPeriod.DAY, schedule, 10));
        assertEquals(30, bucket.add(day1Later, CalendarPeriod.DAY, schedule, 20));
    }

    @Test
    void reconcileResetsCountOnPeriodRollover() {
        FixedRateBucket bucket = new FixedRateBucket();
        long day1 = instant(ZonedDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneId.of("UTC")));
        long day2 = instant(ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, ZoneId.of("UTC")));

        bucket.add(day1, CalendarPeriod.DAY, schedule, 50);
        assertEquals(50, bucket.reconcile(day1, CalendarPeriod.DAY, schedule));

        // a new calendar day - the counter resets to zero before the new usage is recorded
        assertEquals(0, bucket.reconcile(day2, CalendarPeriod.DAY, schedule));
        assertEquals(5, bucket.add(day2, CalendarPeriod.DAY, schedule, 5));
    }

    @Test
    void retryAfterSecondsMatchesTimeUntilNextPeriod() {
        FixedRateBucket bucket = new FixedRateBucket();
        long now = instant(ZonedDateTime.of(2026, 9, 9, 22, 0, 0, 0, ZoneId.of("UTC")));
        bucket.reconcile(now, CalendarPeriod.DAY, schedule);

        // 2 hours until UTC midnight
        assertEquals(2 * 60 * 60, bucket.retryAfterSeconds(now, CalendarPeriod.DAY, schedule));
    }

    @Test
    void resetsAtMillisIsTheNextPeriodStart() {
        FixedRateBucket bucket = new FixedRateBucket();
        long now = instant(ZonedDateTime.of(2026, 9, 9, 22, 0, 0, 0, ZoneId.of("UTC")));

        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 10, 0, 0, 0, 0, ZoneId.of("UTC"))),
                bucket.resetsAtMillis(now, CalendarPeriod.DAY, schedule));
    }

    @Test
    void oldFloatingWindowJsonDeserializesWithoutThrowing() {
        // shape of a pre-rollout floating-window RateBucket record
        String oldJson = "{\"window\":\"DAY\",\"sums\":[1,2,3],\"sum\":6,\"start\":100,\"end\":124}";

        FixedRateBucket bucket = ProxyUtil.convertToObject(oldJson, FixedRateBucket.class);

        assertTrue(bucket != null);
        assertEquals(0, bucket.getCount());
        // the sentinel default never matches a real computed period start, so the very first
        // reconcile after rollout always resets - the implicit one-time reset the rollout relies on
        long now = instant(ZonedDateTime.of(2026, 9, 9, 10, 0, 0, 0, ZoneId.of("UTC")));
        assertEquals(0, bucket.reconcile(now, CalendarPeriod.DAY, schedule));
    }
}
