package com.epam.deltix.dial.proxy.config;

import lombok.Data;

@Data
public class Limit {
    private long minute = Long.MAX_VALUE;
    private long day = Long.MAX_VALUE;

    public boolean isPositive() {
        return minute > 0 && day > 0;
    }
}