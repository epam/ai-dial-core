package com.epam.aidial.core.storage.util;

import java.time.Duration;

/**
 * Histogram buckets for the Core timers; the default Micrometer histogram starts at 1 ms.
 */
public final class LatencyBuckets {

    /**
     * Waits, holds and stalls: an uncontended lock wait (a Redis round-trip), event-loop lag and task start delay
     * are well under 1 ms, a lock held across a publication or a stalled loop can run for tens of seconds.
     */
    public static final Duration[] WAIT = {
        Duration.ofNanos(100_000), Duration.ofNanos(250_000), Duration.ofNanos(500_000), Duration.ofMillis(1),
        Duration.ofMillis(2), Duration.ofMillis(5), Duration.ofMillis(10), Duration.ofMillis(25), Duration.ofMillis(50),
        Duration.ofMillis(100), Duration.ofMillis(250), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofSeconds(5),
        Duration.ofSeconds(10), Duration.ofSeconds(30)
    };

    private LatencyBuckets() {
    }
}
