package com.epam.aidial.core.server.vertx;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
        long startedBefore = meterRegistry.get("dial_async_task_start_delay").timer().count();
        double activeBefore = activeTasks();
        CompletableFuture<Void> release = new CompletableFuture<>();
        CompletableFuture<Double> activeDuringTask = new CompletableFuture<>();

        CompletableFuture<String> result = executor.submit(() -> {
            activeDuringTask.complete(activeTasks());
            release.get(5, TimeUnit.SECONDS);
            return "done";
        }).toCompletionStage().toCompletableFuture();

        assertEquals(activeBefore + 1, activeDuringTask.get(5, TimeUnit.SECONDS));
        release.complete(null);
        assertEquals("done", result.get(5, TimeUnit.SECONDS));
        assertEquals(activeBefore, activeTasks());
        assertEquals(startedBefore + 1, meterRegistry.get("dial_async_task_start_delay").timer().count());
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

    private double activeTasks() {
        return meterRegistry.get("dial_async_tasks_active").gauge().value();
    }
}
