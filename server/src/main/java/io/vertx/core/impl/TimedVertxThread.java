package io.vertx.core.impl;

import com.epam.aidial.core.storage.util.LatencyBuckets;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.spi.VertxThreadFactory;

import java.util.concurrent.TimeUnit;

/**
 * Event-loop thread that times each outermost context dispatch (a handler, a future callback); nested dispatches
 * count in their parent. Netty work outside a dispatch (codecs, TLS, flushes) is not timed.
 *
 * <p>In Vert.x's package because {@link VertxThread#executeEnd()} is package-private: this works on the classpath
 * only (one runtime package), and an upgrade of Vert.x must keep {@code TimedVertxThreadTest} green.
 */
public class TimedVertxThread extends VertxThread {

    private final Timer timer;

    TimedVertxThread(Runnable target, String name, long maxExecTime, TimeUnit maxExecTimeUnit) {
        super(target, name, false, maxExecTime, maxExecTimeUnit);
        // tagged like netty_eventexecutor_tasks_pending: one hot loop is invisible in an aggregate
        timer = Timer.builder("dial_event_loop_task_duration")
                .description("Time an event-loop thread runs one outermost context dispatch")
                .tag("name", name)
                .serviceLevelObjectives(LatencyBuckets.WAIT_BUCKETS)
                .register(Metrics.globalRegistry);
    }

    @Override
    void executeEnd() {
        // the context is already restored: null means the outermost dispatch ends. startTime() is the one
        // executeStart() set for the blocked thread checker, and super resets it
        if (context == null) {
            timer.record(System.nanoTime() - startTime(), TimeUnit.NANOSECONDS);
        }
        super.executeEnd();
    }

    /**
     * Registered in META-INF/services/io.vertx.core.spi.VertxServiceProvider. Workers keep the plain thread.
     */
    public static class Factory implements VertxThreadFactory {

        @Override
        public VertxThread newVertxThread(Runnable target, String name, boolean worker, long maxExecTime, TimeUnit maxExecTimeUnit) {
            return worker
                    ? VertxThreadFactory.super.newVertxThread(target, name, true, maxExecTime, maxExecTimeUnit)
                    : new TimedVertxThread(target, name, maxExecTime, maxExecTimeUnit);
        }
    }
}
