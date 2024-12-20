package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import org.apache.commons.lang3.math.NumberUtils;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TokenRateLimit {

    private final RateBucket minute = new RateBucket(RateWindow.MINUTE);
    private final RateBucket day = new RateBucket(RateWindow.DAY);
    private final RateBucket week = new RateBucket(RateWindow.WEEK);
    private final RateBucket month = new RateBucket(RateWindow.MONTH);

    public void add(long timestamp, long count) {
        minute.add(timestamp, count);
        day.add(timestamp, count);
        week.add(timestamp, count);
        month.add(timestamp, count);
    }

    public RateLimitResult update(long timestamp, Limit limit) {
        long minuteTotal = minute.update(timestamp);
        long dayTotal = day.update(timestamp);
        long weekTotal = week.update(timestamp);
        long monthTotal = month.update(timestamp);

        boolean result = minuteTotal >= limit.getMinute() || dayTotal >= limit.getDay()
                || weekTotal >= limit.getWeek() || monthTotal >= limit.getMonth();
        if (result) {
            String errorMsg = String.format(
                    "Hit token rate limit. Minute limit: %d / %d tokens. Day limit: %d / %d tokens. Week limit: %d / %d tokens. Month limit: %d / %d tokens.",
                    minuteTotal, limit.getMinute(), dayTotal, limit.getDay(), weekTotal, limit.getWeek(), monthTotal, limit.getMonth());
            long minuteRetryAfter = minute.retryAfter(limit.getMinute());
            long dayRetryAfter = day.retryAfter(limit.getDay());
            long weekRetryAfter = week.retryAfter(limit.getWeek());
            long monthRetryAfter = month.retryAfter(limit.getMonth());
            long retryAfter = NumberUtils.max(minuteRetryAfter, dayRetryAfter, weekRetryAfter, monthRetryAfter);
            return new RateLimitResult(HttpStatus.TOO_MANY_REQUESTS, errorMsg, retryAfter);
        } else {
            return RateLimitResult.SUCCESS;
        }
    }

    public void update(long timestamp, LimitStats limitStats) {
        long minuteTotal = minute.update(timestamp);
        long dayTotal = day.update(timestamp);
        long weekTotal = week.update(timestamp);
        long monthTotal = month.update(timestamp);
        limitStats.getDayTokenStats().setUsed(dayTotal);
        limitStats.getMinuteTokenStats().setUsed(minuteTotal);
        limitStats.getWeekTokenStats().setUsed(weekTotal);
        limitStats.getMonthTokenStats().setUsed(monthTotal);
    }
}
