package com.epam.aidial.core.server.limiter;

import java.math.BigDecimal;
import java.math.MathContext;

public record SpendCap(CalendarPeriod period, BigDecimal used, BigDecimal total, long resetsAtMillis) {

    public boolean isExceeded() {
        return used.compareTo(total) >= 0;
    }

    public BigDecimal ratio() {
        return used.divide(total, MathContext.DECIMAL64);
    }
}
