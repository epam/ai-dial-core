package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.RateLimitSchedule;
import com.epam.aidial.core.storage.http.HttpStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class TokenRateLimitTest {

    private final RateLimitSchedule schedule = new RateLimitSchedule();

    @Test
    public void update_minuteOnly_breachIsMinuteTokens() {
        long now = System.currentTimeMillis();
        TokenRateLimit rateLimit = new TokenRateLimit();
        rateLimit.add(now, schedule, 10);
        Limit limit = new Limit();
        limit.setMinute(10);

        RateLimitResult result = rateLimit.update(now, schedule, limit);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, result.status());
        assertEquals(LimitBreach.Kind.TOKENS, result.breach().kind());
        assertEquals(LimitBreach.Window.MINUTE, result.breach().window());
        assertEquals(result.replyAfterSeconds(), result.breach().retryAfterSeconds());
    }

    @Test
    public void update_dayFires_breachIsDayTokens() {
        long now = System.currentTimeMillis();
        TokenRateLimit rateLimit = new TokenRateLimit();
        rateLimit.add(now, schedule, 10);
        Limit limit = new Limit();
        limit.setMinute(10);
        limit.setDay(10);

        RateLimitResult result = rateLimit.update(now, schedule, limit);

        assertEquals(LimitBreach.Kind.TOKENS, result.breach().kind());
        assertEquals(LimitBreach.Window.DAY, result.breach().window());
        assertEquals(result.replyAfterSeconds(), result.breach().retryAfterSeconds());
    }
}
