package io.vertx.core.impl;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Relies on Vert.x internals: a Vert.x upgrade that moves the dispatch hooks fails here.
 */
class TimedVertxThreadTest {

    private final Vertx vertx = Vertx.vertx();
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
    void factoryIsLoadedForEventLoopsOnly() throws Exception {
        CompletableFuture<Thread> eventLoop = new CompletableFuture<>();
        vertx.runOnContext(ignored -> eventLoop.complete(Thread.currentThread()));
        Thread worker = vertx.executeBlocking(Thread::currentThread).toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);

        assertInstanceOf(TimedVertxThread.class, eventLoop.get(5, TimeUnit.SECONDS));
        assertFalse(worker instanceof TimedVertxThread);
    }

    @Test
    void outermostDispatchIsTimedOnceWithNestedDispatchesInside() throws Exception {
        Context context = vertx.getOrCreateContext();
        String loop = runOnContext(context, () -> {
            ((ContextInternal) context).dispatch(nested -> sleep(50));
            sleep(50);
        }).getName();

        // recorded after the handler returns; the registry is new in this test, so the count starts at 0
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (timer(loop).count() == 0) {
            assertTrue(System.nanoTime() < deadline, "the dispatch should be recorded");
            Thread.sleep(1);
        }
        Thread.sleep(100);
        assertEquals(1, timer(loop).count());
        assertTrue(timer(loop).max(TimeUnit.MILLISECONDS) >= 100, "the nested dispatch counts in its parent");
    }

    @Test
    void blockedThreadCheckerStartTimeIsKept() throws Exception {
        CompletableFuture<Long> startTimeDuringTask = new CompletableFuture<>();
        TimedVertxThread loop = runOnContext(vertx.getOrCreateContext(),
                () -> startTimeDuringTask.complete(((VertxThread) Thread.currentThread()).startTime()));

        assertNotEquals(0, startTimeDuringTask.get(5, TimeUnit.SECONDS));
        // reset by super.executeEnd(): a leftover start time makes the checker report the idle loop as blocked
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (loop.startTime() != 0) {
            assertTrue(System.nanoTime() < deadline, "the start time should be reset after the dispatch");
            Thread.sleep(1);
        }
    }

    private static TimedVertxThread runOnContext(Context context, Runnable task) throws Exception {
        CompletableFuture<TimedVertxThread> done = new CompletableFuture<>();
        context.runOnContext(ignored -> {
            task.run();
            done.complete((TimedVertxThread) Thread.currentThread());
        });
        return done.get(5, TimeUnit.SECONDS);
    }

    private Timer timer(String loop) {
        return meterRegistry.get("dial_event_loop_task_duration").tag("name", loop).timer();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
