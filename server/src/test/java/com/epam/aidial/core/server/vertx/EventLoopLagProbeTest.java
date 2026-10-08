package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EventLoopLagProbeTest {

    private final Vertx vertx = Vertx.vertx(new VertxOptions().setEventLoopPoolSize(1));
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() {
        Metrics.addRegistry(meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        Metrics.removeRegistry(meterRegistry);
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void recordsStallOfBlockedEventLoop() throws Exception {
        EventLoopLagProbe.start(vertx);
        vertx.runOnContext(ignored -> sleep(500));
        Thread.sleep(1_000);

        Timer lag = meterRegistry.get("dial_event_loop_lag").timer();
        assertTrue(lag.count() > 0, "the probe should have run");
        assertTrue(lag.max(TimeUnit.MILLISECONDS) >= 300, "the 500 ms stall should show up as lag, max=" + lag.max(TimeUnit.MILLISECONDS));
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
