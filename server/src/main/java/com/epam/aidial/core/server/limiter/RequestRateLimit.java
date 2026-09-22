package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RequestRateLimit {
    private final RateBucket hour = new RateBucket(RateWindow.HOUR);
    private final FixedRateBucket day = new FixedRateBucket();

    public RateLimitResult check(long timestamp, RateLimitSchedule schedule, Limit limit, long count) {
        long hourTotal = hour.update(timestamp);
        long dayTotal = day.reconcile(timestamp, CalendarPeriod.DAY, schedule);

        boolean result = hourTotal >= limit.getRequestHour() || dayTotal >= limit.getRequestDay();
        if (result) {
            String errorMsg = String.format("Hit request rate limit. Hour limit: %d / %d requests. Day limit: %d / %d requests.",
                    hourTotal, limit.getRequestHour(), dayTotal, limit.getRequestDay());
            long retryAfter = 0;
            if (hourTotal >= limit.getRequestHour()) {
                retryAfter = Math.max(retryAfter, hour.retryAfter(limit.getRequestHour()));
            }
            if (dayTotal >= limit.getRequestDay()) {
                retryAfter = Math.max(retryAfter, day.retryAfterSeconds(timestamp, CalendarPeriod.DAY, schedule));
            }
            List<String> limits = new ArrayList<>();
            StringBuilder displayError = new StringBuilder("You've exceeded your");
            if (dayTotal >= limit.getRequestDay()) {
                limits.add("daily");
            }
            if (hourTotal >= limit.getRequestHour()) {
                limits.add("hourly");
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
            displayError.append(" request limit");
            if (limits.size() > 1) {
                displayError.append('s');
            }
            return new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, errorMsg, displayError.toString(), retryAfter);
        } else {
            hour.add(timestamp, count);
            day.add(timestamp, CalendarPeriod.DAY, schedule, count);
            return RateLimitResult.SUCCESS;
        }
    }

    public void update(long timestamp, RateLimitSchedule schedule, LimitStats limitStats) {
        long hourTotal = hour.update(timestamp);
        long dayTotal = day.reconcile(timestamp, CalendarPeriod.DAY, schedule);
        limitStats.getDayRequestStats().setUsed(dayTotal);
        limitStats.getHourRequestStats().setUsed(hourTotal);
    }
}
