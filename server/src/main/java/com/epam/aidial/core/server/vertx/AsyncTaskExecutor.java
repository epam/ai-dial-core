package com.epam.aidial.core.server.vertx;

import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import lombok.AllArgsConstructor;

import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

import static java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor;

@AllArgsConstructor
public class AsyncTaskExecutor {

    private static final Executor EXECUTOR = newVirtualThreadPerTaskExecutor();

    private final Vertx vertx;

    public <T> Future<T> submit(Callable<T> blockingCall) {
        ContextInternal context = (ContextInternal) vertx.getOrCreateContext();
        Promise<T> promise = Promise.promise();

        Runnable task = () -> context.dispatch(() -> {
            try {
                T output = blockingCall.call();
                promise.complete(output);
            } catch (Throwable error) {
                promise.fail(error);
            }
        });

        EXECUTOR.execute(task);
        return promise.future();
    }
}