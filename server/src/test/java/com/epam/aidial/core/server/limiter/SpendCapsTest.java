package com.epam.aidial.core.server.limiter;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpendCapsTest {

    private static final String TOMORROW = "2026-10-24T00:00:00Z";
    private static final String IN_5_DAYS = "2026-10-28T00:00:00Z";
    private static final String IN_20_DAYS = "2026-11-12T00:00:00Z";

    private static SpendCap day(long total, String used, String resetsAt) {
        return cap(CalendarPeriod.DAY, BigDecimal.valueOf(total), used, resetsAt);
    }

    private static SpendCap week(long total, String used, String resetsAt) {
        return cap(CalendarPeriod.WEEK, BigDecimal.valueOf(total), used, resetsAt);
    }

    private static SpendCap month(long total, String used, String resetsAt) {
        return cap(CalendarPeriod.MONTH, BigDecimal.valueOf(total), used, resetsAt);
    }

    private static SpendCap cap(CalendarPeriod period, BigDecimal total, String used, String resetsAt) {
        return new SpendCap(period, new BigDecimal(used), total, Instant.parse(resetsAt).toEpochMilli());
    }

    @Test
    void select_allAllowed_picksFullest() {
        SpendCaps caps = SpendCaps.of(List.of(
                day(10, "8.2", TOMORROW), week(100, "20", IN_5_DAYS), month(1000, "100", IN_20_DAYS)));

        assertEquals(3, caps.windows().size());
        assertEquals(CalendarPeriod.DAY, caps.reported().period());
        assertFalse(caps.reported().isExceeded());
    }

    @Test
    void select_exceeded_picksLatestReset() {
        SpendCaps caps = SpendCaps.of(List.of(
                day(10, "10", TOMORROW), week(100, "100", IN_5_DAYS), month(1000, "500", IN_20_DAYS)));

        assertEquals(CalendarPeriod.WEEK, caps.reported().period());
        assertTrue(caps.reported().isExceeded());
    }

    @Test
    void select_exceededBeatsFuller() {
        SpendCaps caps = SpendCaps.of(List.of(
                day(10, "9.9", TOMORROW), month(1000, "1000", IN_20_DAYS)));

        assertEquals(CalendarPeriod.MONTH, caps.reported().period());
    }

    @Test
    void select_tieOnResetPrefersLongerPeriod() {
        SpendCaps caps = SpendCaps.of(List.of(
                week(100, "100", IN_5_DAYS), month(1000, "1000", IN_5_DAYS)));

        assertEquals(CalendarPeriod.MONTH, caps.reported().period());
    }

    @Test
    void select_tieOnRatioPrefersLongerPeriod() {
        SpendCaps caps = SpendCaps.of(List.of(
                day(10, "5", TOMORROW), week(100, "50", IN_5_DAYS)));

        assertEquals(CalendarPeriod.WEEK, caps.reported().period());
    }

    @Test
    void select_noWindows_isNull() {
        assertNull(SpendCaps.select(List.of()));
    }

    @Test
    void of_skipsUnlimitedWindows() {
        SpendCaps caps = SpendCaps.of(List.of(
                day(Long.MAX_VALUE, "1", TOMORROW),
                week(0, "1", IN_5_DAYS),
                cap(CalendarPeriod.MONTH, BigDecimal.valueOf(1L << 53), "1", IN_20_DAYS)));

        assertTrue(caps.windows().isEmpty());
        assertNull(caps.reported());

        SpendCaps kept = SpendCaps.of(List.of(month((1L << 53) - 1, "1", IN_20_DAYS)));

        assertEquals(1, kept.windows().size());
        assertNotNull(kept.reported());
    }

    @Test
    void of_noCap_isEmpty() {
        assertEquals(SpendCaps.EMPTY, SpendCaps.of(List.of()));
    }
}
