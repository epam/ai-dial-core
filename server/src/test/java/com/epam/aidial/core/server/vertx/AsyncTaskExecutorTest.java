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

class AsyncTaskExecutorTest {

    private final Vertx vertx = Vertx.vertx();
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

    @BeforeEach
    void setUp() throws InterruptedException {
        Metrics.addRegistry(meterRegistry);
        awaitNoActiveTasks();
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

        long startDelaysBefore = meterRegistry.find("dial_async_task_start_delay").timers().stream().mapToLong(Timer::count).sum();
        CompletableFuture<String> result = executor.submit(() -> {
            activeDuringTask.complete(activeTasks());
            release.get(5, TimeUnit.SECONDS);
            return "done";
        }).toCompletionStage().toCompletableFuture();

        assertEquals(1, activeDuringTask.get(5, TimeUnit.SECONDS));
        release.complete(null);
        assertEquals("done", result.get(5, TimeUnit.SECONDS));
        assertEquals(0, activeTasks());
        assertEquals(startDelaysBefore + 1, meterRegistry.get("dial_async_task_start_delay").timer().count());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void failedTaskIsNoLongerActive(boolean useVirtualThreads) {
        AsyncTaskExecutor executor = new AsyncTaskExecutor(vertx, new JsonObject().put("useVirtualThreads", useVirtualThreads));
        CompletableFuture<Object> result = executor.submit(() -> {
            throw new IllegalStateException("boom");
        }).toCompletionStage().toCompletableFuture();

        assertThrows(Exception.class, () -> result.get(5, TimeUnit.SECONDS));
        assertEquals(0, activeTasks());
    }

    @Test
    void rejectedTaskIsNoLongerActive() throws Exception {
        Vertx closed = Vertx.vertx();
        AsyncTaskExecutor executor = new AsyncTaskExecutor(closed, new JsonObject().put("useVirtualThreads", false));
        closed.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        Future<String> result = executor.submit(() -> "never runs");

        assertTrue(result.failed());
        assertInstanceOf(RejectedExecutionException.class, result.cause());
        assertEquals(0, activeTasks());
    }

    @Test
    void taskDroppedByClosingVertxIsNoLongerActive() throws Exception {
        Vertx closing = Vertx.vertx(new VertxOptions().setWorkerPoolSize(1));
        AsyncTaskExecutor executor = new AsyncTaskExecutor(closing, new JsonObject().put("useVirtualThreads", false));
        CompletableFuture<Void> running = new CompletableFuture<>();
        executor.submit(() -> {
            running.complete(null);
            return new CompletableFuture<>().get(5, TimeUnit.SECONDS);
        });
        running.get(5, TimeUnit.SECONDS);
        executor.submit(() -> "queued behind the blocked task, dropped on close");
        assertEquals(2, activeTasks());

        closing.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        awaitNoActiveTasks();
    }

    /**
     * The gauge is JVM-wide: wait for tasks left over by earlier test classes so the exact counts here hold.
     */
    private void awaitNoActiveTasks() throws InterruptedException {
        // not registered until the first executor class load, then nothing can be running
        if (meterRegistry.find("dial_async_tasks_active").gauge() == null) {
            return;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (activeTasks() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, activeTasks(), "tasks left running by earlier tests");
    }

    private double activeTasks() {
        return meterRegistry.get("dial_async_tasks_active").gauge().value();
    }
}
