package com.epam.aidial.core.server.vertx;

import com.epam.aidial.core.storage.service.LockService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Schedules a no-op on every event loop and records how late it runs: a busy or blocked loop runs it late.
 */
public final class EventLoopLagProbe {

    private static final long PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final Timer LAG_TIMER = Timer.builder("dial_event_loop_lag")
            .description("How late a task scheduled on an event loop runs")
            .serviceLevelObjectives(LockService.LATENCY_BUCKETS)
            .register(Metrics.globalRegistry);
    /**
     * Last probe run per event loop of the vertx started last; null when stopped.
     * Static like the gauge reading it: every start() in this JVM feeds the same gauge.
     */
    private static volatile AtomicLongArray lastRunAt;

    static {
        // the lag timer only records once a stalled loop runs the probe again, this gauge shows a stall still going on
        Gauge.builder("dial_event_loop_stall", EventLoopLagProbe::longestSinceLastRunSeconds)
                .description("Longest time any event loop has not run its probe")
                .baseUnit("seconds")
                .register(Metrics.globalRegistry);
    }

    private EventLoopLagProbe() {
    }

    public static void start(Vertx vertx) {
        List<EventExecutor> loops = new ArrayList<>();
        vertx.nettyEventLoopGroup().forEach(loops::add);
        long now = System.nanoTime();
        AtomicLongArray runs = new AtomicLongArray(loops.size());
        for (int i = 0; i < loops.size(); i++) {
            runs.set(i, now);
        }
        // fixed delay, not fixed rate: a fixed rate would run the missed probes back to back and hide the stall
        for (int i = 0; i < loops.size(); i++) {
            loops.get(i).scheduleWithFixedDelay(probe(runs, i), PERIOD_NANOS, PERIOD_NANOS, TimeUnit.NANOSECONDS);
        }
        lastRunAt = runs;
    }

    /**
     * Stops reading the probes; the probes themselves end with the vertx.
     */
    public static void stop() {
        lastRunAt = null;
    }

    private static Runnable probe(AtomicLongArray runs, int loop) {
        // touched only by the loop's own thread
        long[] expectedAt = {System.nanoTime() + PERIOD_NANOS};
        return () -> {
            long now = System.nanoTime();
            LAG_TIMER.record(Math.max(0, now - expectedAt[0]), TimeUnit.NANOSECONDS);
            expectedAt[0] = now + PERIOD_NANOS;
            runs.set(loop, now);
        };
    }

    private static double longestSinceLastRunSeconds() {
        AtomicLongArray runs = lastRunAt;
        if (runs == null) {
            return 0;
        }
        long now = System.nanoTime();
        long longest = 0;
        for (int i = 0; i < runs.length(); i++) {
            longest = Math.max(longest, now - runs.get(i));
        }
        return longest / 1e9;
    }
}
