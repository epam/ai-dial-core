package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.server.data.CostItemLimitStats;
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

    private static CostItemLimitStats stats(BigDecimal total, String used, String resetsAt) {
        CostItemLimitStats stats = new CostItemLimitStats();
        stats.setTotal(total);
        stats.setUsed(new BigDecimal(used));
        stats.setResetsAt(resetsAt);
        return stats;
    }

    private static CostItemLimitStats stats(long total, String used, String resetsAt) {
        return stats(BigDecimal.valueOf(total), used, resetsAt);
    }

    @Test
    void select_allAllowed_picksFullest() {
        SpendCaps caps = SpendCaps.of(
                stats(10, "8.2", TOMORROW), stats(100, "20", IN_5_DAYS), stats(1000, "100", IN_20_DAYS));

        assertEquals(3, caps.windows().size());
        assertEquals(CalendarPeriod.DAY, caps.reported().period());
        assertFalse(caps.reported().isExceeded());
    }

    @Test
    void select_exceeded_picksLatestReset() {
        SpendCaps caps = SpendCaps.of(
                stats(10, "10", TOMORROW), stats(100, "100", IN_5_DAYS), stats(1000, "500", IN_20_DAYS));

        assertEquals(CalendarPeriod.WEEK, caps.reported().period());
        assertTrue(caps.reported().isExceeded());
    }

    @Test
    void select_exceededBeatsFuller() {
        SpendCaps caps = SpendCaps.of(
                stats(10, "9.9", TOMORROW), null, stats(1000, "1000", IN_20_DAYS));

        assertEquals(CalendarPeriod.MONTH, caps.reported().period());
    }

    @Test
    void select_tieOnResetPrefersLongerPeriod() {
        SpendCaps caps = SpendCaps.of(
                null, stats(100, "100", IN_5_DAYS), stats(1000, "1000", IN_5_DAYS));

        assertEquals(CalendarPeriod.MONTH, caps.reported().period());
    }

    @Test
    void select_tieOnRatioPrefersLongerPeriod() {
        SpendCaps caps = SpendCaps.of(
                stats(10, "5", TOMORROW), stats(100, "50", IN_5_DAYS), null);

        assertEquals(CalendarPeriod.WEEK, caps.reported().period());
    }

    @Test
    void select_noWindows_isNull() {
        assertNull(SpendCaps.select(List.of()));
    }

    @Test
    void of_skipsUnlimitedWindows() {
        SpendCaps caps = SpendCaps.of(
                stats(Long.MAX_VALUE, "1", TOMORROW),
                stats(0, "1", IN_5_DAYS),
                stats(BigDecimal.valueOf(1L << 53), "1", IN_20_DAYS));

        assertTrue(caps.windows().isEmpty());
        assertNull(caps.reported());

        SpendCaps kept = SpendCaps.of(null, null, stats((1L << 53) - 1, "1", IN_20_DAYS));

        assertEquals(1, kept.windows().size());
        assertNotNull(kept.reported());
    }

    @Test
    void of_noCap_isEmpty() {
        assertEquals(SpendCaps.EMPTY, SpendCaps.of(null, null, null));
    }

    @Test
    void of_parsesResetInstant() {
        SpendCaps caps = SpendCaps.of(stats(10, "1", "2026-10-24T00:00:00+02:00"), null, null);

        assertEquals(Instant.parse("2026-10-23T22:00:00Z").toEpochMilli(), caps.reported().resetsAtMillis());
    }
}
