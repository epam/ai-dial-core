package com.epam.aidial.core.server.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.json.JsonObject;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadFactory;

import static java.util.concurrent.Executors.newThreadPerTaskExecutor;

/**
 * The executor runs tasks in virtual threads.
 */
public class AsyncTaskExecutor {

    private static final Executor VIRTUAL_THREAD_PER_TASK_EXECUTOR;

    static {
        ThreadFactory threadFactory = Thread.ofVirtual().name("dial.x-virtual-thread-", 0).factory();
        VIRTUAL_THREAD_PER_TASK_EXECUTOR = newThreadPerTaskExecutor(threadFactory);
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
        if (!useVirtualThreads) {
            return vertx.executeBlocking(blockingCall, false);
        }
        ContextInternal context = (ContextInternal) vertx.getOrCreateContext();
        Promise<T> promise = context.promise();

        Runnable task = () -> context.dispatch(() -> {
            try {
                T output = blockingCall.call();
                promise.complete(output);
            } catch (Throwable error) {
                promise.fail(error);
            }
        });

        VIRTUAL_THREAD_PER_TASK_EXECUTOR.execute(task);
        return promise.future();
    }
}