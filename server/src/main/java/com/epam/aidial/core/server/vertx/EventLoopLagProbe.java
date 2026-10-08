package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.netty.util.concurrent.EventExecutor;
import io.vertx.core.Vertx;

import java.util.concurrent.TimeUnit;

/**
 * Schedules a no-op on every event loop and records how late it runs: a busy or blocked loop runs it late.
 */
public final class EventLoopLagProbe {

    private static final long PERIOD_NANOS = TimeUnit.MILLISECONDS.toNanos(100);

    private EventLoopLagProbe() {
    }

    public static void start(Vertx vertx) {
        Timer lagTimer = Timer.builder("dial_event_loop_lag")
                .description("How late a task scheduled on an event loop runs")
                .publishPercentileHistogram()
                .register(Metrics.globalRegistry);
        // fixed delay, not fixed rate: a fixed rate would run the missed probes back to back and hide the stall
        for (EventExecutor loop : vertx.nettyEventLoopGroup()) {
            loop.scheduleWithFixedDelay(probe(lagTimer), PERIOD_NANOS, PERIOD_NANOS, TimeUnit.NANOSECONDS);
        }
    }

    private static Runnable probe(Timer lagTimer) {
        // touched only by the loop's own thread
        long[] expectedAt = {System.nanoTime() + PERIOD_NANOS};
        return () -> {
            long now = System.nanoTime();
            lagTimer.record(Math.max(0, now - expectedAt[0]), TimeUnit.NANOSECONDS);
            expectedAt[0] = now + PERIOD_NANOS;
        };
    }
}
