package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
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
 * The gauge is JVM-wide and other test classes leave tasks behind, so every assertion is a delta from a value read first.
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
    void tracksActiveTasksAndStartDelay(boolean useVirtualThreads) throws Exception {
        AsyncTaskExecutor executor = new AsyncTaskExecutor(vertx, new JsonObject().put("useVirtualThreads", useVirtualThreads));
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Double> activeDuringTask = new CompletableFuture<>();
        double activeBefore = activeTasks();
        long startDelaysBefore = startDelays();

        CompletableFuture<String> result = executor.submit(() -> {
            activeDuringTask.complete(activeTasks());
            release.get(5, TimeUnit.SECONDS);
            return "done";
        }).toCompletionStage().toCompletableFuture();

        assertEquals(activeBefore + 1, activeDuringTask.get(5, TimeUnit.SECONDS));
        release.complete(null);
        assertEquals("done", result.get(5, TimeUnit.SECONDS));
        assertEquals(activeBefore, activeTasks());
        assertEquals(startDelaysBefore + 1, startDelays());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedTaskIsNoLongerActive(boolean useVirtualThreads) {
        AsyncTaskExecutor executor = new AsyncTaskExecutor(vertx, new JsonObject().put("useVirtualThreads", useVirtualThreads));
        double activeBefore = activeTasks();

        CompletableFuture<Object> result = executor.submit(() -> {
            throw new IllegalStateException("boom");
        }).toCompletionStage().toCompletableFuture();

        assertThrows(Exception.class, () -> result.get(5, TimeUnit.SECONDS));
        assertEquals(activeBefore, activeTasks());
    }

    @Test
    void rejectedTaskIsNoLongerActive() throws Exception {
        Vertx closed = Vertx.vertx();
        AsyncTaskExecutor executor = new AsyncTaskExecutor(closed, new JsonObject().put("useVirtualThreads", false));
        closed.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        double activeBefore = activeTasks();

        Future<String> result = executor.submit(() -> "never runs");

        assertTrue(result.failed());
        assertInstanceOf(RejectedExecutionException.class, result.cause());
        assertEquals(activeBefore, activeTasks());
    }

    private double activeTasks() {
        return meterRegistry.get("dial_async_tasks_active").gauge().value();
    }

    private long startDelays() {
        return meterRegistry.get("dial_async_task_start_delay").timer().count();
    }
}
