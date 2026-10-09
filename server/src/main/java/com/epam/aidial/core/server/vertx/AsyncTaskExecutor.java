package com.epam.aidial.core.server.vertx;

import com.epam.aidial.core.storage.service.LockService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.impl.VertxInternal;
import io.vertx.core.json.JsonObject;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
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
            .serviceLevelObjectives(LockService.LATENCY_BUCKETS)
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

    /**
     * Submitted tasks counted in ACTIVE_TASKS that have not started: whoever removes a task first uncounts it.
     */
    private final Set<Object> notStarted = ConcurrentHashMap.newKeySet();

    /**
     * Set once the close hook has run: vertx shuts the worker pool down only after its close hooks,
     * so a task submitted in between is still queued, then dropped.
     */
    private volatile boolean closing;

    public AsyncTaskExecutor(Vertx vertx, JsonObject settings) {
        this.vertx = vertx;
        useVirtualThreads = settings.getBoolean("useVirtualThreads", Boolean.TRUE);
        if (!useVirtualThreads) {
            // closing vertx drops the tasks queued in the worker pool without running or failing them
            ((VertxInternal) vertx).addCloseHook(completion -> {
                closing = true;
                notStarted.forEach(this::uncountIfNotStarted);
                completion.complete();
            });
        }
    }

    /**
     * Submits the task asynchronously.
     *
     * @param blockingCall - the task to be executed
     * @return the result of the blocking cal
     */
    public <T> Future<T> submit(Callable<T> blockingCall) {
        long submitted = System.nanoTime();
        Object task = new Object();
        Callable<T> measuredCall = () -> {
            // false when the close hook has already uncounted the task
            boolean counted = notStarted.remove(task);
            try {
                START_DELAY_TIMER.record(System.nanoTime() - submitted, TimeUnit.NANOSECONDS);
                return blockingCall.call();
            } finally {
                if (counted) {
                    ACTIVE_TASKS.decrementAndGet();
                }
            }
        };
        ACTIVE_TASKS.incrementAndGet();
        notStarted.add(task);
        Future<T> result = execute(measuredCall);
        // a closed vertx fails the future without ever running the task; a closing one may queue the task, then drop it
        if (result.failed() || closing) {
            uncountIfNotStarted(task);
        }
        return result;
    }

    private void uncountIfNotStarted(Object task) {
        if (notStarted.remove(task)) {
            ACTIVE_TASKS.decrementAndGet();
        }
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