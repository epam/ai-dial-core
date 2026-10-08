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

    private static final String STALL_GAUGE = "dial_event_loop_stall";
    private static final long PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private EventLoopLagProbe() {
    }

    /**
     * @return the stall gauge, to be passed to {@link #stop(Gauge)} when the vertx is closed
     */
    public static Gauge start(Vertx vertx) {
        // register() would hand back a gauge still watching an earlier vertx: the latest vertx wins
        Gauge previous = Metrics.globalRegistry.find(STALL_GAUGE).gauge();
        if (previous != null) {
            Metrics.globalRegistry.remove(previous);
        }
        Timer lagTimer = Timer.builder("dial_event_loop_lag")
                .description("How late a task scheduled on an event loop runs")
                .serviceLevelObjectives(LockService.LATENCY_BUCKETS)
                .register(Metrics.globalRegistry);
        List<EventExecutor> loops = new ArrayList<>();
        vertx.nettyEventLoopGroup().forEach(loops::add);
        long now = System.nanoTime();
        AtomicLongArray lastRunAt = new AtomicLongArray(loops.size());
        for (int i = 0; i < loops.size(); i++) {
            lastRunAt.set(i, now);
        }
        // the lag timer only records once a stalled loop runs the probe again, this gauge shows a stall still going on
        // held weakly: the scheduled probes keep lastRunAt alive while the loops run
        Gauge stallGauge = Gauge.builder(STALL_GAUGE, lastRunAt, EventLoopLagProbe::longestSinceLastRunSeconds)
                .description("Longest time any event loop has not run its probe")
                .baseUnit("seconds")
                .register(Metrics.globalRegistry);
        // fixed delay, not fixed rate: a fixed rate would run the missed probes back to back and hide the stall
        for (int i = 0; i < loops.size(); i++) {
            loops.get(i).scheduleWithFixedDelay(probe(lagTimer, lastRunAt, i), PERIOD_NANOS, PERIOD_NANOS, TimeUnit.NANOSECONDS);
        }
        return stallGauge;
    }

    public static void stop(Gauge stallGauge) {
        // remove() goes by id: leave alone a gauge a later start() registered under the same name
        if (Metrics.globalRegistry.find(STALL_GAUGE).gauge() == stallGauge) {
            Metrics.globalRegistry.remove(stallGauge);
        }
    }

    private static Runnable probe(Timer lagTimer, AtomicLongArray lastRunAt, int loop) {
        // touched only by the loop's own thread
        long[] expectedAt = {System.nanoTime() + PERIOD_NANOS};
        return () -> {
            long now = System.nanoTime();
            lagTimer.record(Math.max(0, now - expectedAt[0]), TimeUnit.NANOSECONDS);
            expectedAt[0] = now + PERIOD_NANOS;
            lastRunAt.set(loop, now);
        };
    }

    private static double longestSinceLastRunSeconds(AtomicLongArray lastRunAt) {
        long now = System.nanoTime();
        long longest = 0;
        for (int i = 0; i < lastRunAt.length(); i++) {
            longest = Math.max(longest, now - lastRunAt.get(i));
        }
        return longest / 1e9;
    }
}
