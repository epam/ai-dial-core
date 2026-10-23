package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.server.data.CostItemLimitStats;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

/**
 * The capped calendar windows of a user and the one of them to report.
 *
 * @param windows capped windows in DAY, WEEK, MONTH order
 * @param reported the window to report, null when there is no cap
 */
public record SpendCaps(List<SpendCap> windows, @Nullable SpendCap reported) {

    public static final SpendCaps EMPTY = new SpendCaps(List.of(), null);

    private static final BigDecimal UNLIMITED_THRESHOLD = BigDecimal.valueOf(1L << 53);

    public static SpendCaps of(@Nullable CostItemLimitStats day, @Nullable CostItemLimitStats week, @Nullable CostItemLimitStats month) {
        List<SpendCap> windows = new ArrayList<>(3);
        addIfCapped(windows, CalendarPeriod.DAY, day);
        addIfCapped(windows, CalendarPeriod.WEEK, week);
        addIfCapped(windows, CalendarPeriod.MONTH, month);
        if (windows.isEmpty()) {
            return EMPTY;
        }
        return new SpendCaps(List.copyOf(windows), select(windows));
    }

    /**
     * Picks the exceeded window that resets last, otherwise the fullest one; ties go to the longer period.
     */
    @Nullable
    static SpendCap select(List<SpendCap> windows) {
        List<SpendCap> exceeded = windows.stream().filter(SpendCap::isExceeded).toList();
        if (!exceeded.isEmpty()) {
            return exceeded.stream().max(Comparator.comparingLong(SpendCap::resetsAtMillis).thenComparing(SpendCap::period)).orElse(null);
        }
        return windows.stream().max(Comparator.comparing(SpendCap::ratio).thenComparing(SpendCap::period)).orElse(null);
    }

    private static void addIfCapped(List<SpendCap> windows, CalendarPeriod period, @Nullable CostItemLimitStats stats) {
        if (stats == null || stats.getTotal().signum() <= 0 || stats.getTotal().compareTo(UNLIMITED_THRESHOLD) >= 0) {
            return;
        }
        long resetsAtMillis = OffsetDateTime.parse(stats.getResetsAt()).toInstant().toEpochMilli();
        windows.add(new SpendCap(period, stats.getUsed(), stats.getTotal(), resetsAtMillis));
    }
}
