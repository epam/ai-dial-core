package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventLoopLagProbeTest {

    private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        EventLoopLagProbe.stop();
        Metrics.removeRegistry(meterRegistry);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
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
        vertx.runOnContext(ignored -> release.join());
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
        // the only loop ran this after stop(), so no probe is in flight
        CompletableFuture<Void> drained = new CompletableFuture<>();
        vertx.runOnContext(ignored -> drained.complete(null));
        drained.get(5, TimeUnit.SECONDS);
        long recorded = lag.count();
        Thread.sleep(300);

        assertEquals(recorded, lag.count(), "a cancelled probe records nothing");
    }

    private Timer lagTimer() {
        return meterRegistry.get("dial_event_loop_lag").timer();
    }

    private double stallSeconds() {
        return meterRegistry.get("dial_event_loop_stall").gauge().value();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
