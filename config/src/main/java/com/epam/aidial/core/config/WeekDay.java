package com.epam.aidial.core.config;

import java.time.DayOfWeek;

public enum WeekDay {
    Mon(DayOfWeek.MONDAY),
    Tue(DayOfWeek.TUESDAY),
    Wed(DayOfWeek.WEDNESDAY),
    Thu(DayOfWeek.THURSDAY),
    Fri(DayOfWeek.FRIDAY),
    Sat(DayOfWeek.SATURDAY),
    Sun(DayOfWeek.SUNDAY);

    private final DayOfWeek dayOfWeek;

    WeekDay(DayOfWeek dayOfWeek) {
        this.dayOfWeek = dayOfWeek;
    }

    public DayOfWeek toDayOfWeek() {
        return dayOfWeek;
    }
}
