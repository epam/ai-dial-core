package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.RateLimitSchedule;
import lombok.experimental.UtilityClass;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;

/**
 * Computes DAY/WEEK/MONTH period boundaries against a deployment-wide {@link RateLimitSchedule},
 * using {@link ZonedDateTime} arithmetic throughout so a "day"/"week"/"month" always advances by
 * one calendar unit in local wall-clock time, regardless of how many real hours a DST transition
 * makes that span (23h on a spring-forward day, 25h on a fall-back day).
 */
@UtilityClass
public class CalendarWindowCalculator {

    public long currentPeriodStart(CalendarPeriod period, long timestampMillis, RateLimitSchedule schedule) {
        ZonedDateTime now = now(timestampMillis, schedule);
        return periodStart(period, now, schedule).toInstant().toEpochMilli();
    }

    /**
     * The instant the period containing {@code timestampMillis} ends - i.e. when the next period
     * starts. This is both what {@link FixedRateBucket}/{@link CostFixedRateBucket} advance to on
     * rollover, and what gets reported to callers as {@code resetsAt}.
     */
    public long nextPeriodStart(CalendarPeriod period, long timestampMillis, RateLimitSchedule schedule) {
        ZonedDateTime now = now(timestampMillis, schedule);
        ZonedDateTime periodStart = periodStart(period, now, schedule);
        return advance(period, periodStart).toInstant().toEpochMilli();
    }

    private ZonedDateTime now(long timestampMillis, RateLimitSchedule schedule) {
        ZoneId zone = ZoneId.of(schedule.getTimezone());
        return Instant.ofEpochMilli(timestampMillis).atZone(zone);
    }

    private ZonedDateTime periodStart(CalendarPeriod period, ZonedDateTime now, RateLimitSchedule schedule) {
        return switch (period) {
            case DAY -> dayPeriodStart(now, schedule);
            case WEEK -> weekPeriodStart(now, schedule);
            case MONTH -> monthPeriodStart(now, schedule);
        };
    }

    private ZonedDateTime advance(CalendarPeriod period, ZonedDateTime periodStart) {
        return switch (period) {
            case DAY -> periodStart.plusDays(1);
            case WEEK -> periodStart.plusWeeks(1);
            case MONTH -> periodStart.plusMonths(1);
        };
    }

    private ZonedDateTime dayPeriodStart(ZonedDateTime now, RateLimitSchedule schedule) {
        ZonedDateTime candidate = todayReset(now, schedule);
        return candidate.isAfter(now) ? candidate.minusDays(1) : candidate;
    }

    private ZonedDateTime weekPeriodStart(ZonedDateTime now, RateLimitSchedule schedule) {
        ZonedDateTime todayReset = todayReset(now, schedule);
        DayOfWeek weekStartDay = schedule.getWeekStartDay().toDayOfWeek();
        int daysSinceWeekStart = (todayReset.getDayOfWeek().getValue() - weekStartDay.getValue() + 7) % 7;
        ZonedDateTime candidate = todayReset.minusDays(daysSinceWeekStart);
        return candidate.isAfter(now) ? candidate.minusWeeks(1) : candidate;
    }

    private ZonedDateTime monthPeriodStart(ZonedDateTime now, RateLimitSchedule schedule) {
        ZonedDateTime candidate = now.toLocalDate().withDayOfMonth(1).atTime(resetTime(schedule)).atZone(now.getZone());
        return candidate.isAfter(now) ? candidate.minusMonths(1) : candidate;
    }

    private ZonedDateTime todayReset(ZonedDateTime now, RateLimitSchedule schedule) {
        return now.toLocalDate().atTime(resetTime(schedule)).atZone(now.getZone());
    }

    private LocalTime resetTime(RateLimitSchedule schedule) {
        return LocalTime.parse(schedule.getResetTime());
    }
}
