package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.util.concurrent.EventExecutor;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoopLagProbeTest {

    // two loops: the stall gauge has to look past a healthy loop, and a failed start() has a probe to cancel
    private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        EventLoopLagProbe.stop();
        Metrics.removeRegistry(meterRegistry);
        close(vertx);
    }

    @Test
    void recordsNearZeroLagOnIdleLoops() throws Exception {
        EventLoopLagProbe.start(vertx);
        Timer lag = lagTimer();
        Await.until(() -> lag.count() >= 4, () -> "the probes should have run");

        // the lag is how late the probe runs past its period, not the period itself
        assertTrue(lag.mean(TimeUnit.MILLISECONDS) < 50, () -> "idle loops should not lag, mean=" + lag.mean(TimeUnit.MILLISECONDS));
    }

    @Test
    void recordsStallOfBlockedEventLoop() throws Exception {
        EventLoopLagProbe.start(vertx);
        vertx.runOnContext(ignored -> sleep(500));

        // recorded once the loop runs the probe again, after the block; a healthy loop lags a few ms at most
        Timer lag = lagTimer();
        Await.until(() -> lag.max(TimeUnit.MILLISECONDS) >= 300, () -> "the 500 ms stall should show up as lag, max=" + lag.max(TimeUnit.MILLISECONDS));
    }

    @Test
    void reportsStallWhileEventLoopIsStillBlocked() throws Exception {
        EventLoopLagProbe.start(vertx);
        CompletableFuture<Void> release = new CompletableFuture<>();
        // the second loop: the gauge reports the longest stall over all loops, not the first loop's
        loops(vertx).get(1).execute(release::join);
        try {
            Await.until(() -> stallSeconds() >= 0.4, () -> "the ongoing stall should show up, stall=" + stallSeconds());
            EventLoopLagProbe.stop();
            assertEquals(0, stallSeconds(), "a stopped probe reports no stall");
        } finally {
            release.complete(null);
        }
    }

    @Test
    void stopCancelsProbes() throws Exception {
        EventLoopLagProbe.start(vertx);
        Timer lag = lagTimer();
        Await.until(() -> lag.count() > 0, () -> "the probe should have run");

        EventLoopLagProbe.stop();
        drain(loops(vertx));
        long recorded = lag.count();
        Thread.sleep(300);

        assertEquals(recorded, lag.count(), "a cancelled probe records nothing");
    }

    @Test
    void startCancelsProbesOfEarlierStart() throws Exception {
        Vertx earlier = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
        try {
            EventLoopLagProbe.start(earlier);
            EventLoopLagProbe.start(vertx);
            // stop() cancels the probes of vertx only: a probe of earlier left behind by the second start() keeps recording
            EventLoopLagProbe.stop();
            drain(loops(earlier));
            drain(loops(vertx));
            Timer lag = lagTimer();
            long recorded = lag.count();
            Thread.sleep(300);

            assertEquals(recorded, lag.count(), "the second start() should have cancelled the probes of the first");
        } finally {
            close(earlier);
        }
    }

    @Test
    void failedStartCancelsItsProbesAndKeepsThoseOfEarlierStart() throws Exception {
        Vertx broken = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(2));
        try {
            // the first loop accepts its probe, the terminated second one rejects it: start() has a probe to cancel
            List<EventExecutor> brokenLoops = loops(broken);
            brokenLoops.get(1).shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            EventLoopLagProbe.start(vertx);
            Timer lag = lagTimer();
            Await.until(() -> lag.count() > 0, () -> "the probe should have run");

            assertThrows(RejectedExecutionException.class, () -> EventLoopLagProbe.start(broken));
            // the probes of vertx stayed in place, so stop() cancels them; the probe of the first broken loop was cancelled by start()
            EventLoopLagProbe.stop();
            drain(loops(vertx));
            drain(brokenLoops.subList(0, 1));
            long recorded = lag.count();
            Thread.sleep(300);

            assertEquals(recorded, lag.count(), "neither the probes of vertx nor the probe of the first broken loop should record");
        } finally {
            close(broken);
        }
    }

    private Timer lagTimer() {
        return meterRegistry.get("dial_event_loop_lag").timer();
    }

    private double stallSeconds() {
        return meterRegistry.get("dial_event_loop_stall").gauge().value();
    }

    private static List<EventExecutor> loops(Vertx vertx) {
        List<EventExecutor> loops = new ArrayList<>();
        vertx.nettyEventLoopGroup().forEach(loops::add);
        return loops;
    }

    /**
     * Returns once every loop ran a task submitted after the cancel: no probe is in flight any more.
     */
    private static void drain(List<EventExecutor> loops) throws Exception {
        for (EventExecutor loop : loops) {
            loop.submit(() -> { }).get(5, TimeUnit.SECONDS);
        }
    }

    private static void close(Vertx vertx) throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
