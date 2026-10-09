package com.epam.aidial.core.server.vertx;

import com.epam.aidial.core.storage.util.LatencyBuckets;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.vertx.core.Vertx;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Schedules a probe on every event loop every 100 ms and records how late it runs: a busy or blocked loop runs it late.
 * One vertx per JVM: the lag timer and stall gauge are static, start() replaces the probes of an earlier start() and
 * stop() cancels them whichever vertx started them.
 */
public final class EventLoopLagProbe {

    private static final long PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    private static final Timer LAG_TIMER = Timer.builder("dial_event_loop_lag")
            .description("How late a task scheduled on an event loop runs")
            .serviceLevelObjectives(LatencyBuckets.WAIT_BUCKETS)
            .register(Metrics.globalRegistry);
    /**
     * The probes of the vertx started last, swapped as one so the gauge never reads the runs of cancelled probes.
     * Static like the gauge reading it: every start() in this JVM feeds the same gauge.
     */
    private static final AtomicReference<Probes> PROBES = new AtomicReference<>(Probes.NONE);

    static {
        // the lag timer only records once a stalled loop runs the probe again, this gauge shows a stall still going on
        Gauge.builder("dial_event_loop_stall", EventLoopLagProbe::measureLongestStallSeconds)
                .description("Longest time any event loop has not run its probe")
                .baseUnit("seconds")
                .register(Metrics.globalRegistry);
    }

    private EventLoopLagProbe() {
    }

    /**
     * Probes the loops of this vertx; the probes of a vertx started earlier are cancelled.
     */
    public static void start(Vertx vertx) {
        List<EventExecutor> loops = new ArrayList<>();
        vertx.nettyEventLoopGroup().forEach(loops::add);
        long now = System.nanoTime();
        AtomicLongArray runs = new AtomicLongArray(loops.size());
        List<ScheduledFuture<?>> scheduled = new ArrayList<>(loops.size());
        Probes probes = new Probes(runs, scheduled);
        try {
            for (int i = 0; i < loops.size(); i++) {
                runs.set(i, now);
                // fixed delay, not fixed rate: a fixed rate would run the missed probes back to back and hide the stall
                scheduled.add(loops.get(i).scheduleWithFixedDelay(createProbe(runs, i), PERIOD_NANOS, PERIOD_NANOS, TimeUnit.NANOSECONDS));
            }
        } catch (RuntimeException e) {
            // a loop that is shutting down rejects the probe; the ones scheduled before it would run on with no way to cancel them
            probes.cancel();
            throw e;
        }
        PROBES.getAndSet(probes).cancel();
    }

    public static void stop() {
        PROBES.getAndSet(Probes.NONE).cancel();
    }

    private static Runnable createProbe(AtomicLongArray runs, int loop) {
        return () -> {
            long now = System.nanoTime();
            // due one period after the previous run, or after start() for the first run
            LAG_TIMER.record(now - runs.get(loop) - PERIOD_NANOS, TimeUnit.NANOSECONDS);
            runs.set(loop, now);
        };
    }

    private static double measureLongestStallSeconds() {
        AtomicLongArray runs = PROBES.get().lastRunAt();
        long now = System.nanoTime();
        long longest = 0;
        for (int i = 0; i < runs.length(); i++) {
            longest = Math.max(longest, now - runs.get(i));
        }
        return longest / 1e9;
    }

    /**
     * Last probe run per event loop, and the scheduled probes to cancel.
     */
    private record Probes(AtomicLongArray lastRunAt, List<ScheduledFuture<?>> scheduled) {

        static final Probes NONE = new Probes(new AtomicLongArray(0), List.of());

        void cancel() {
            scheduled.forEach(probe -> probe.cancel(false));
        }
    }
}
