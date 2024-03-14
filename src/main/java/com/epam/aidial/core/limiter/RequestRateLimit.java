package com.epam.aidial.core.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.data.LimitStats;
import com.epam.aidial.core.util.HttpStatus;
import lombok.Data;

@Data
public class RequestRateLimit {
    private final RateBucket hour = new RateBucket(RateWindow.HOUR);
    private final RateBucket day = new RateBucket(RateWindow.DAY);

    public RateLimitResult check(long timestamp, Limit limit, long count) {
        long hourTotal = hour.update(timestamp);
        long dayTotal = day.update(timestamp);

        boolean result = hourTotal >= limit.getRequestHour() || dayTotal >= limit.getRequestDay();
        if (result) {
            String errorMsg = String.format("""
                            Hit request rate limit:
                             - hour limit: %d / %d requests
                             - day limit: %d / %d requests
                            """,
                    hourTotal, limit.getRequestHour(), dayTotal, limit.getRequestDay());
            return new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, errorMsg);
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
