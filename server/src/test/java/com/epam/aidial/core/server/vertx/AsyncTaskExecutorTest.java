package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The meters are JVM-wide and other test classes leave tasks behind, so every assertion is a delta from a value read first.
 */
class AsyncTaskExecutorTest {

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

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void recordsStartDelayAndDuration(boolean useVirtualThreads) throws Exception {
        AsyncTaskExecutor executor = new AsyncTaskExecutor(vertx, new JsonObject().put("useVirtualThreads", useVirtualThreads));
        CompletableFuture<Double> queuedDuringTask = new CompletableFuture<>();
        double queuedBefore = queuedTasks();
        long startDelaysBefore = startDelays();
        long durationsBefore = durations().count();

        CompletableFuture<String> result = executor.submit(() -> {
            queuedDuringTask.complete(queuedTasks());
            Thread.sleep(300);
            return "done";
        }).toCompletionStage().toCompletableFuture();

        assertEquals("done", result.get(5, TimeUnit.SECONDS));
        assertEquals(queuedBefore, queuedDuringTask.get(5, TimeUnit.SECONDS), "a running task is no longer queued");
        assertEquals(queuedBefore, queuedTasks());
        assertEquals(startDelaysBefore + 1, startDelays());
        assertEquals(durationsBefore + 1, durations().count());
        assertTrue(durations().max(TimeUnit.MILLISECONDS) >= 250);
    }

    @Test
    void taskWaitingForWorkerIsQueued() throws Exception {
        Vertx oneWorker = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1));
        try {
            AsyncTaskExecutor executor = new AsyncTaskExecutor(oneWorker, new JsonObject().put("useVirtualThreads", false));
            CompletableFuture<Void> started = new CompletableFuture<>();
            CompletableFuture<Void> release = new CompletableFuture<>();
            double queuedBefore = queuedTasks();

            Future<Object> first = executor.submit(() -> {
                started.complete(null);
                return release.get(5, TimeUnit.SECONDS);
            });
            started.get(5, TimeUnit.SECONDS);
            Future<String> second = executor.submit(() -> "second");
            assertEquals(queuedBefore + 1, queuedTasks());

            release.complete(null);
            assertEquals("second", second.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS));
            first.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertEquals(queuedBefore, queuedTasks());
        } finally {
            oneWorker.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedTaskRecordsDuration(boolean useVirtualThreads) {
        AsyncTaskExecutor executor = new AsyncTaskExecutor(vertx, new JsonObject().put("useVirtualThreads", useVirtualThreads));
        double queuedBefore = queuedTasks();
        long durationsBefore = durations().count();

        CompletableFuture<Object> result = executor.submit(() -> {
            throw new IllegalStateException("boom");
        }).toCompletionStage().toCompletableFuture();

        assertThrows(Exception.class, () -> result.get(5, TimeUnit.SECONDS));
        assertEquals(queuedBefore, queuedTasks());
        assertEquals(durationsBefore + 1, durations().count());
    }

    @Test
    void rejectedTaskIsNoLongerQueued() throws Exception {
        Vertx closed = Vertx.vertx();
        AsyncTaskExecutor executor = new AsyncTaskExecutor(closed, new JsonObject().put("useVirtualThreads", false));
        closed.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        double queuedBefore = queuedTasks();
        long startDelaysBefore = startDelays();

        Future<String> result = executor.submit(() -> "never runs");

        assertTrue(result.failed());
        assertInstanceOf(RejectedExecutionException.class, result.cause());
        assertEquals(queuedBefore, queuedTasks());
        assertEquals(startDelaysBefore, startDelays());
    }

    private double queuedTasks() {
        return meterRegistry.get("dial_async_tasks_queued").gauge().value();
    }

    private long startDelays() {
        return meterRegistry.get("dial_async_task_start_delay").timer().count();
    }

    private Timer durations() {
        return meterRegistry.get("dial_async_task_duration").timer();
    }
}
