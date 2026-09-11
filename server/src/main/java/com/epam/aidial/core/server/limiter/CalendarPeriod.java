package com.epam.aidial.core.server.limiter;

/**
 * A fixed calendar rate-limit window: usage resets all at once at a deterministic boundary
 * computed from the deployment-wide {@link com.epam.aidial.core.config.RateLimitSchedule}, unlike
 * {@link RateWindow}'s MINUTE/HOUR, which keep aging usage out gradually.
 */
public enum CalendarPeriod {
    DAY,
    WEEK,
    MONTH
}
