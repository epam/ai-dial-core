package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CostLimit {
    private BigDecimal minute = BigDecimal.valueOf(Long.MAX_VALUE);
    private BigDecimal day = BigDecimal.valueOf(Long.MAX_VALUE);
    private BigDecimal week = BigDecimal.valueOf(Long.MAX_VALUE);
    private BigDecimal month = BigDecimal.valueOf(Long.MAX_VALUE);

    @JsonIgnore
    public boolean isPositive() {
        return minute.compareTo(BigDecimal.ZERO) > 0
                || day.compareTo(BigDecimal.ZERO) > 0
                || week.compareTo(BigDecimal.ZERO) > 0
                || month.compareTo(BigDecimal.ZERO) > 0;
    }

    @JsonIgnore
    public boolean hasLimits() {
        return minute.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0
                || day.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0
                || week.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0
                || month.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0;
    }
}

