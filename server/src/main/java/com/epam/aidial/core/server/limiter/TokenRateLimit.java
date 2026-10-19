package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenRateLimit {

    private final RateBucket minute = new RateBucket(RateWindow.MINUTE);
    private final FixedRateBucket day = new FixedRateBucket();
    private final FixedRateBucket week = new FixedRateBucket();
    private final FixedRateBucket month = new FixedRateBucket();

    public void add(long timestamp, RateLimitSchedule schedule, long count) {
        minute.add(timestamp, count);
        day.add(timestamp, CalendarPeriod.DAY, schedule, count);
        week.add(timestamp, CalendarPeriod.WEEK, schedule, count);
        month.add(timestamp, CalendarPeriod.MONTH, schedule, count);
    }

    public RateLimitResult update(long timestamp, RateLimitSchedule schedule, Limit limit) {
        long minuteTotal = minute.update(timestamp);
        long dayTotal = day.reconcile(timestamp, CalendarPeriod.DAY, schedule);
        long weekTotal = week.reconcile(timestamp, CalendarPeriod.WEEK, schedule);
        long monthTotal = month.reconcile(timestamp, CalendarPeriod.MONTH, schedule);

        boolean result = minuteTotal >= limit.getMinute() || dayTotal >= limit.getDay()
                || weekTotal >= limit.getWeek() || monthTotal >= limit.getMonth();
        if (result) {
            String errorMsg = String.format(
                    "Hit token rate limit. Minute limit: %d / %d tokens. Day limit: %d / %d tokens. Week limit: %d / %d tokens. Month limit: %d / %d tokens.",
                    minuteTotal, limit.getMinute(), dayTotal, limit.getDay(), weekTotal, limit.getWeek(), monthTotal, limit.getMonth());
            long retryAfter = 0;
            if (minuteTotal >= limit.getMinute()) {
                retryAfter = Math.max(retryAfter, minute.retryAfter(limit.getMinute()));
            }
            if (dayTotal >= limit.getDay()) {
                retryAfter = Math.max(retryAfter, day.retryAfterSeconds(timestamp, CalendarPeriod.DAY, schedule));
            }
            if (weekTotal >= limit.getWeek()) {
                retryAfter = Math.max(retryAfter, week.retryAfterSeconds(timestamp, CalendarPeriod.WEEK, schedule));
            }
            if (monthTotal >= limit.getMonth()) {
                retryAfter = Math.max(retryAfter, month.retryAfterSeconds(timestamp, CalendarPeriod.MONTH, schedule));
            }
            List<String> limits = new ArrayList<>();
            StringBuilder displayError = new StringBuilder("You've exceeded your");
            if (monthTotal >= limit.getMonth()) {
                limits.add("monthly");
            }
            if (weekTotal >= limit.getWeek()) {
                limits.add("weekly");
            }
            if (dayTotal >= limit.getDay()) {
                limits.add("daily");
            }
            if (minuteTotal >= limit.getMinute()) {
                limits.add("minute");
            }
            for (int i = 0; i < limits.size(); i++) {
                if (i > 0) {
                    if (i == limits.size() - 1) {
                        displayError.append(" and");
                    } else {
                        displayError.append(',');
                    }
                }
                displayError.append(' ');
                displayError.append(limits.get(i));
            }
            displayError.append(" token limit");
            if (limits.size() > 1) {
                displayError.append('s');
            }
            return new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, errorMsg, displayError.toString(), retryAfter);
        } else {
            return RateLimitResult.SUCCESS;
        }
    }

    public void update(long timestamp, RateLimitSchedule schedule, LimitStats limitStats) {
        long minuteTotal = minute.update(timestamp);
        long dayTotal = day.reconcile(timestamp, CalendarPeriod.DAY, schedule);
        long weekTotal = week.reconcile(timestamp, CalendarPeriod.WEEK, schedule);
        long monthTotal = month.reconcile(timestamp, CalendarPeriod.MONTH, schedule);
        limitStats.getDayTokenStats().setUsed(dayTotal);
        limitStats.getMinuteTokenStats().setUsed(minuteTotal);
        limitStats.getWeekTokenStats().setUsed(weekTotal);
        limitStats.getMonthTokenStats().setUsed(monthTotal);
    }
}
