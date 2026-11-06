package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.config.WeekDay;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CalendarWindowCalculatorTest {

    private static long instant(ZonedDateTime dateTime) {
        return dateTime.toInstant().toEpochMilli();
    }

    private static RateLimitSchedule schedule(String timezone, WeekDay weekStartDay, String resetTime) {
        RateLimitSchedule schedule = new RateLimitSchedule();
        schedule.setTimezone(timezone);
        schedule.setWeekStartDay(weekStartDay);
        schedule.setResetTime(resetTime);
        return schedule;
    }

    @Test
    void defaultSchedule_isUtcMidnightMonday() {
        RateLimitSchedule schedule = new RateLimitSchedule();
        assertEquals("UTC", schedule.getTimezone());
        assertEquals(WeekDay.Mon, schedule.getWeekStartDay());
        assertEquals("00:00", schedule.getResetTime());
    }

    @Test
    void dayPeriod_beforeAndAfterResetTime() {
        RateLimitSchedule schedule = schedule("UTC", WeekDay.Mon, "09:00");
        ZoneId utc = ZoneId.of("UTC");

        // 08:00, before today's 09:00 reset -> still in yesterday's period
        long beforeReset = instant(ZonedDateTime.of(2026, 9, 10, 8, 0, 0, 0, utc));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 9, 9, 0, 0, 0, utc)),
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.DAY, beforeReset, schedule));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 10, 9, 0, 0, 0, utc)),
                CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.DAY, beforeReset, schedule));

        // 10:00, after today's 09:00 reset -> today's period
        long afterReset = instant(ZonedDateTime.of(2026, 9, 10, 10, 0, 0, 0, utc));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 10, 9, 0, 0, 0, utc)),
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.DAY, afterReset, schedule));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 11, 9, 0, 0, 0, utc)),
                CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.DAY, afterReset, schedule));
    }

    @Test
    void weekPeriod_rollsBackToConfiguredStartDay() {
        RateLimitSchedule schedule = schedule("UTC", WeekDay.Mon, "09:00");
        ZoneId utc = ZoneId.of("UTC");

        // Wednesday 2026-09-09 (before its own 09:00 reset does not matter for week boundary,
        // only Monday 09:00 does) at 08:00 -> still within the week starting Monday 09:00
        long wednesdayMorning = instant(ZonedDateTime.of(2026, 9, 9, 8, 0, 0, 0, utc));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 7, 9, 0, 0, 0, utc)), // Monday 2026-09-07
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.WEEK, wednesdayMorning, schedule));

        // Monday 2026-09-07 at 08:00, before this Monday's 09:00 reset -> previous week
        long mondayBeforeReset = instant(ZonedDateTime.of(2026, 9, 7, 8, 0, 0, 0, utc));
        assertEquals(
                instant(ZonedDateTime.of(2026, 8, 31, 9, 0, 0, 0, utc)), // previous Monday
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.WEEK, mondayBeforeReset, schedule));

        // Monday 2026-09-07 at 10:00, after reset -> this week
        long mondayAfterReset = instant(ZonedDateTime.of(2026, 9, 7, 10, 0, 0, 0, utc));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 7, 9, 0, 0, 0, utc)),
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.WEEK, mondayAfterReset, schedule));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 14, 9, 0, 0, 0, utc)),
                CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.WEEK, mondayAfterReset, schedule));
    }

    @Test
    void weekPeriod_supportsNonMondayStartDay() {
        RateLimitSchedule schedule = schedule("UTC", WeekDay.Sun, "00:00");
        ZoneId utc = ZoneId.of("UTC");

        // Wednesday 2026-09-09 -> week started Sunday 2026-09-06
        long wednesday = instant(ZonedDateTime.of(2026, 9, 9, 12, 0, 0, 0, utc));
        assertEquals(
                instant(ZonedDateTime.of(2026, 9, 6, 0, 0, 0, 0, utc)),
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.WEEK, wednesday, schedule));
    }

    @Test
    void monthPeriod_alwaysLandsOnTheFirst() {
        RateLimitSchedule schedule = schedule("UTC", WeekDay.Mon, "00:00");
        ZoneId utc = ZoneId.of("UTC");

        // mid-February (28-day month in 2026, non-leap) -> period started Feb 1
        long midFeb = instant(ZonedDateTime.of(2026, 2, 15, 12, 0, 0, 0, utc));
        assertEquals(
                instant(ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, utc)),
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.MONTH, midFeb, schedule));
        // next period start is March 1st regardless of February's length
        assertEquals(
                instant(ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, utc)),
                CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.MONTH, midFeb, schedule));

        // first of the month, before reset -> previous month
        long firstOfMonthEarly = instant(ZonedDateTime.of(2026, 3, 1, 0, 0, 0, 0, utc).minusSeconds(1));
        assertEquals(
                instant(ZonedDateTime.of(2026, 2, 1, 0, 0, 0, 0, utc)),
                CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.MONTH, firstOfMonthEarly, schedule));
    }

    @Test
    void dayPeriod_springForwardTransitionIsTwentyThreeHours() {
        // Europe/Warsaw switches to DST on 2026-03-29, clocks jump 02:00 -> 03:00
        RateLimitSchedule schedule = schedule("Europe/Warsaw", WeekDay.Mon, "00:00");
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");

        long duringTransitionDay = instant(ZonedDateTime.of(2026, 3, 29, 12, 0, 0, 0, warsaw));
        long periodStart = CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.DAY, duringTransitionDay, schedule);
        long nextStart = CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.DAY, duringTransitionDay, schedule);

        assertEquals(instant(ZonedDateTime.of(2026, 3, 29, 0, 0, 0, 0, warsaw)), periodStart);
        assertEquals(instant(ZonedDateTime.of(2026, 3, 30, 0, 0, 0, 0, warsaw)), nextStart);
        // the transition day is only 23 real hours, not 24
        assertEquals(23 * 60 * 60 * 1000L, nextStart - periodStart);
    }

    @Test
    void dayPeriod_fallBackTransitionIsTwentyFiveHours() {
        // Europe/Warsaw switches off DST on 2026-10-25, clocks fall back 03:00 -> 02:00
        RateLimitSchedule schedule = schedule("Europe/Warsaw", WeekDay.Mon, "00:00");
        ZoneId warsaw = ZoneId.of("Europe/Warsaw");

        long duringTransitionDay = instant(ZonedDateTime.of(2026, 10, 25, 12, 0, 0, 0, warsaw));
        long periodStart = CalendarWindowCalculator.currentPeriodStart(CalendarPeriod.DAY, duringTransitionDay, schedule);
        long nextStart = CalendarWindowCalculator.nextPeriodStart(CalendarPeriod.DAY, duringTransitionDay, schedule);

        assertEquals(25 * 60 * 60 * 1000L, nextStart - periodStart);
    }
}
