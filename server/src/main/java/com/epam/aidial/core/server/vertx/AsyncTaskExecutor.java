package com.epam.aidial.core.server.vertx;

import com.epam.aidial.core.storage.metrics.LatencyBuckets;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

/**
 * The executor runs tasks in virtual threads.
 */
public class AsyncTaskExecutor {

    private static final Executor VIRTUAL_THREAD_PER_TASK_EXECUTOR;
    // static: Micrometer keeps the first gauge registered under a name
    private static final AtomicInteger QUEUED_TASKS = new AtomicInteger();
    private static final Timer START_DELAY_TIMER = Timer.builder("dial_async_task_start_delay")
            .description("Time from submit() until the task starts running")
            .serviceLevelObjectives(LatencyBuckets.waitBuckets())
            .register(Metrics.globalRegistry);
    private static final Timer DURATION_TIMER = Timer.builder("dial_async_task_duration")
            .description("Time a task runs, from its start until it returns or throws")
            .serviceLevelObjectives(LatencyBuckets.waitBuckets())
            .register(Metrics.globalRegistry);

    static {
        ThreadFactory threadFactory = Thread.ofVirtual().name("dial.x-virtual-thread-", 0).factory();
        VIRTUAL_THREAD_PER_TASK_EXECUTOR = newThreadPerTaskExecutor(threadFactory);
        Gauge.builder("dial_async_tasks_queued", QUEUED_TASKS, AtomicInteger::get)
                .description("Tasks submitted and not started yet")
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
        MeasuredTask<T> task = new MeasuredTask<>(blockingCall);
        Future<T> result;
        try {
            result = execute(task);
        } catch (Throwable e) {
            task.dequeue();
            throw e;
        }
        // rejected by a closed vertx; a task it drops while still queued stays counted
        if (result.failed()) {
            task.dequeue();
        }
        return result;
    }

    private <T> Future<T> execute(MeasuredTask<T> measuredCall) {
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

    // measured on the task's thread: a future listener runs on the event loop, which would add its own delay
    private static final class MeasuredTask<T> implements Callable<T> {

        private final Callable<T> call;
        private final long submitted = System.nanoTime();
        private final AtomicBoolean dequeued = new AtomicBoolean();

        MeasuredTask(Callable<T> call) {
            this.call = call;
            QUEUED_TASKS.incrementAndGet();
        }

        @Override
        public T call() throws Exception {
            dequeue();
            long start = System.nanoTime();
            START_DELAY_TIMER.record(start - submitted, TimeUnit.NANOSECONDS);
            try {
                return call.call();
            } finally {
                DURATION_TIMER.record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }

        void dequeue() {
            if (dequeued.compareAndSet(false, true)) {
                QUEUED_TASKS.decrementAndGet();
            }
        }
    }
}
