package com.epam.aidial.core.server.limiter;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public record SpendCap(CalendarPeriod period, BigDecimal used, BigDecimal total, long resetsAtMillis) {

    public boolean isExceeded() {
        return used.compareTo(total) >= 0;
    }

    public BigDecimal ratio() {
        return used.divide(total, MathContext.DECIMAL64);
    }

    /**
     * The spent share as a whole percent, rounded down so it never claims a threshold the ratio has not reached.
     */
    public long percent() {
        return ratio().movePointRight(2).setScale(0, RoundingMode.DOWN).longValue();
    }

    public long secondsUntilReset(long nowMillis) {
        return Math.max(0, Math.ceilDiv(resetsAtMillis - nowMillis, 1000));
    }
}
