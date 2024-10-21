package com.epam.aidial.core.config;

import lombok.Data;

@Data
public class Limit {
    private long minute = Long.MAX_VALUE;
    private long day = Long.MAX_VALUE;
    private long requestHour = Long.MAX_VALUE;
    private long requestDay = Long.MAX_VALUE;

    public boolean isPositive() {
        return minute > 0 && day > 0 && requestDay > 0 && requestHour > 0;
    }
}