package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.storage.http.HttpStatus;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class RequestRateLimit {
    private final RateBucket hour = new RateBucket(RateWindow.HOUR);
    private final RateBucket day = new RateBucket(RateWindow.DAY);

    public RateLimitResult check(long timestamp, Limit limit, long count) {
        long hourTotal = hour.update(timestamp);
        long dayTotal = day.update(timestamp);

        boolean result = hourTotal >= limit.getRequestHour() || dayTotal >= limit.getRequestDay();
        if (result) {
            String errorMsg = String.format("Hit request rate limit. Hour limit: %d / %d requests. Day limit: %d / %d requests.",
                    hourTotal, limit.getRequestHour(), dayTotal, limit.getRequestDay());
            long hourRetryAfter = hour.retryAfter(limit.getRequestHour());
            long dayRetryAfter = day.retryAfter(limit.getRequestDay());
            long retryAfter = Math.max(hourRetryAfter, dayRetryAfter);
            List<String> limits = new ArrayList<>();
            StringBuilder displayError = new StringBuilder("You've exceeded your");
            if (dayTotal >= limit.getRequestDay()) {
                limits.add("daily");
            }
            if (hourTotal >= limit.getRequestHour()) {
                limits.add("minutely");
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
            day.add(timestamp, count);
            return RateLimitResult.SUCCESS;
        }
    }

    public void update(long timestamp, LimitStats limitStats) {
        long hourTotal = hour.update(timestamp);
        long dayTotal = day.update(timestamp);
        limitStats.getDayRequestStats().setUsed(dayTotal);
        limitStats.getHourRequestStats().setUsed(hourTotal);
    }
}
