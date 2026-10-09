package com.epam.aidial.core.server.vertx;

import com.epam.aidial.core.storage.util.LatencyBuckets;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

/**
 * The executor runs tasks in virtual threads.
 */
public class AsyncTaskExecutor {

    private static final Executor VIRTUAL_THREAD_PER_TASK_EXECUTOR;
    // static: every executor instance shares one gauge, and a gauge only holds a weak reference to its value
    private static final AtomicInteger ACTIVE_TASKS = new AtomicInteger();
    private static final Timer START_DELAY_TIMER = Timer.builder("dial_async_task_start_delay")
            .description("Time from submit() until the task starts running")
            .serviceLevelObjectives(LatencyBuckets.waitBuckets())
            .register(Metrics.globalRegistry);

    static {
        ThreadFactory threadFactory = Thread.ofVirtual().name("dial.x-virtual-thread-", 0).factory();
        VIRTUAL_THREAD_PER_TASK_EXECUTOR = newThreadPerTaskExecutor(threadFactory);
        Gauge.builder("dial_async_tasks_active", ACTIVE_TASKS, AtomicInteger::get)
                .description("Tasks submitted and not finished yet")
                .register(Metrics.globalRegistry);
    }

    private final Vertx vertx;

    /**
     * Use Vertx worker pool
     */
    private final boolean useVirtualThreads;

    public AsyncTaskExecutor(Vertx vertx, JsonObject settings) {
        this.vertx = vertx;
        useVirtualThreads = settings.getBoolean("useVirtualThreads", Boolean.TRUE);
    }

    /**
     * Submits the task asynchronously.
     *
     * @param blockingCall - the task to be executed
     * @return the result of the blocking cal
     */
    public <T> Future<T> submit(Callable<T> blockingCall) {
        long submitted = System.nanoTime();
        ACTIVE_TASKS.incrementAndGet();
        Callable<T> measuredCall = () -> {
            START_DELAY_TIMER.record(System.nanoTime() - submitted, TimeUnit.NANOSECONDS);
            return blockingCall.call();
        };
        Future<T> result = execute(measuredCall);
        // the future completes exactly once. A closed vertx fails it at once and its context can no longer run
        // listeners, so that case is counted here. A task still queued in the worker pool when vertx closes is
        // dropped and stays counted; the pod is going down, at most the last OTLP push sees it.
        if (result.failed()) {
            ACTIVE_TASKS.decrementAndGet();
            return result;
        }
        return result.onComplete(ignored -> ACTIVE_TASKS.decrementAndGet());
    }

    private <T> Future<T> execute(Callable<T> measuredCall) {
        if (!useVirtualThreads) {
            return vertx.executeBlocking(measuredCall, false);
        }
        ContextInternal context = (ContextInternal) vertx.getOrCreateContext();
        Promise<T> promise = context.promise();

        Runnable task = () -> context.dispatch(() -> {
            try {
                T output = measuredCall.call();
                promise.complete(output);
            } catch (Throwable error) {
                promise.fail(error);
            }
        });

        VIRTUAL_THREAD_PER_TASK_EXECUTOR.execute(task);
        return promise.future();
    }
}
