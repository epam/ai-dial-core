package com.epam.aidial.core.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.data.LimitStats;
import com.epam.aidial.core.util.HttpStatus;
import lombok.Data;

@Data
public class TokenRateLimit {

    private final RateBucket minute = new RateBucket(RateWindow.MINUTE);
    private final RateBucket day = new RateBucket(RateWindow.DAY);

    public void add(long timestamp, long count) {
        minute.add(timestamp, count);
        day.add(timestamp, count);
    }

    public RateLimitResult update(long timestamp, Limit limit) {
        long minuteTotal = minute.update(timestamp);
        long dayTotal = day.update(timestamp);

        boolean result = minuteTotal >= limit.getMinute() || dayTotal >= limit.getDay();
        if (result) {
            String errorMsg = String.format("""
                            Hit token rate limit:
                             - minute limit: %d / %d tokens
                             - day limit: %d / %d tokens
                            """,
                    minuteTotal, limit.getMinute(), dayTotal, limit.getDay());
            return new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, errorMsg);
        } else {
            return RateLimitResult.SUCCESS;
        }
    }

    public void update(long timestamp, LimitStats limitStats) {
        long minuteTotal = minute.update(timestamp);
        long dayTotal = day.update(timestamp);
        limitStats.getDayTokenStats().setUsed(dayTotal);
        limitStats.getMinuteTokenStats().setUsed(minuteTotal);
    }
}