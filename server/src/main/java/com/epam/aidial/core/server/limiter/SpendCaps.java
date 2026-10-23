package com.epam.aidial.core.server.limiter;

import java.math.BigDecimal;
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

    /**
     * Keeps the capped windows of {@code candidates}, in their order, and picks the one to report.
     */
    public static SpendCaps of(List<SpendCap> candidates) {
        List<SpendCap> windows = candidates.stream().filter(SpendCaps::isCapped).toList();
        if (windows.isEmpty()) {
            return EMPTY;
        }
        return new SpendCaps(windows, select(windows));
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

    private static boolean isCapped(SpendCap window) {
        return window.total().signum() > 0 && window.total().compareTo(UNLIMITED_THRESHOLD) < 0;
    }
}
