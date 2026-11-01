package com.epam.aidial.core.server.limiter;

/**
 * A fixed calendar rate-limit window: usage resets all at once at a deterministic boundary
 * computed from the deployment-wide {@link com.epam.aidial.core.config.RateLimitSchedule}, unlike
 * {@link RateWindow}'s MINUTE/HOUR, which keep aging usage out gradually.
 */
public enum CalendarPeriod {
    DAY(1440),
    WEEK(10080),
    MONTH(43200);

    private final int nominalMinutes;

    CalendarPeriod(int nominalMinutes) {
        this.nominalMinutes = nominalMinutes;
    }

    /**
     * The period's length in minutes with a month counted as 30 days, as clients that label a window by its length
     * expect it.
     */
    public int nominalMinutes() {
        return nominalMinutes;
    }
}
