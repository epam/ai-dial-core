package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Gauge;
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

import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoopLagProbeTest {

    private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meterRegistry);
    }

    private Gauge stallGauge;

    @AfterEach
    void tearDown() throws Exception {
        if (stallGauge != null) {
            EventLoopLagProbe.stop(stallGauge);
        }
        Metrics.removeRegistry(meterRegistry);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void recordsStallOfBlockedEventLoop() throws Exception {
        stallGauge = EventLoopLagProbe.start(vertx);
        vertx.runOnContext(ignored -> sleep(500));
        Thread.sleep(1_000);

        Timer lag = meterRegistry.get("dial_event_loop_lag").timer();
        assertTrue(lag.count() > 0, "the probe should have run");
        assertTrue(lag.max(TimeUnit.MILLISECONDS) >= 300, "the 500 ms stall should show up as lag, max=" + lag.max(TimeUnit.MILLISECONDS));
    }

    @Test
    void reportsStallWhileEventLoopIsStillBlocked() throws Exception {
        stallGauge = EventLoopLagProbe.start(vertx);
        CompletableFuture<Void> release = new CompletableFuture<>();
        vertx.runOnContext(ignored -> release.join());
        try {
            Thread.sleep(600);

            double stallSeconds = meterRegistry.get("dial_event_loop_stall").gauge().value();
            assertTrue(stallSeconds >= 0.4, "the ongoing stall should show up, stall=" + stallSeconds);
        } finally {
            release.complete(null);
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
