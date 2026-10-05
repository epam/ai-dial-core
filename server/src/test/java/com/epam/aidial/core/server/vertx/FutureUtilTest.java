package com.epam.aidial.core.server.vertx;

import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FutureUtilTest {

    private final Vertx vertx = Vertx.vertx();

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void continuationRunsOnTheCallersContextNotOnTheCompletersContext() throws Exception {
        ContextInternal loop = (ContextInternal) vertx.getOrCreateContext();
        ContextInternal caller = loop.duplicate();
        ContextInternal completer = loop.duplicate();
        Promise<String> shared = Promise.promise();
        CompletableFuture<Context> continuedOn = new CompletableFuture<>();
        CompletableFuture<String> value = new CompletableFuture<>();

        caller.runOnContext(v -> FutureUtil.continueOnCallerContext(shared.future()).onSuccess(result -> {
            continuedOn.complete(Vertx.currentContext());
            value.complete(result);
        }));
        completer.runOnContext(v -> shared.complete("ok"));

        assertSame(caller, continuedOn.get(10, TimeUnit.SECONDS));
        assertEquals("ok", value.get(10, TimeUnit.SECONDS));
    }

    @Test
    void failurePassesThrough() throws Exception {
        ContextInternal caller = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        CompletableFuture<Throwable> failedWith = new CompletableFuture<>();

        caller.runOnContext(v -> FutureUtil.continueOnCallerContext(Future.failedFuture(new IllegalStateException("boom")))
                .onFailure(failedWith::complete));

        assertEquals("boom", failedWith.get(10, TimeUnit.SECONDS).getMessage());
    }

    @Test
    void offAnyVertxContextTheFutureStillCompletes() throws Exception {
        assertEquals("ok", FutureUtil.continueOnCallerContext(Future.succeededFuture("ok"))
                .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
        Future<String> failed = FutureUtil.continueOnCallerContext(Future.failedFuture(new IllegalStateException("boom")));
        assertThrows(ExecutionException.class, () -> failed.toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS));
    }
}
