package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class RequestRateLimitTest {

    private final RateLimitSchedule schedule = new RateLimitSchedule();

    @Test
    public void check_hourOnly_breachIsHourRequests() {
        long now = System.currentTimeMillis();
        RequestRateLimit rateLimit = new RequestRateLimit();
        Limit limit = new Limit();
        limit.setRequestHour(1);
        assertEquals(HttpStatus.OK, rateLimit.check(now, schedule, limit, 1).status());

        RateLimitResult result = rateLimit.check(now, schedule, limit, 1);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, result.status());
        assertEquals(LimitBreach.Kind.REQUESTS, result.breach().kind());
        assertEquals(LimitBreach.Window.HOUR, result.breach().window());
        assertEquals(result.replyAfterSeconds(), result.breach().retryAfterSeconds());
    }

    @Test
    public void check_dayFires_breachIsDayRequests() {
        long now = System.currentTimeMillis();
        RequestRateLimit rateLimit = new RequestRateLimit();
        Limit limit = new Limit();
        limit.setRequestHour(1);
        limit.setRequestDay(1);
        assertEquals(HttpStatus.OK, rateLimit.check(now, schedule, limit, 1).status());

        RateLimitResult result = rateLimit.check(now, schedule, limit, 1);

        assertEquals(LimitBreach.Kind.REQUESTS, result.breach().kind());
        assertEquals(LimitBreach.Window.DAY, result.breach().window());
        assertEquals(result.replyAfterSeconds(), result.breach().retryAfterSeconds());
    }
}
