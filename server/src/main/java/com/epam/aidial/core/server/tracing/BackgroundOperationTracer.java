package com.epam.aidial.core.server.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;

import java.util.function.Supplier;

/**
 * Traces periodic background operations with one root span per run.
 *
 * <p>Each run gets its own duplicated Vert.x context and the span is kept only in that duplicate's locals, the
 * way every HTTP request keeps its span. The context the timer fired on is shared by all runs and by whatever else
 * uses it, so nothing is written to it and there is nothing to restore. Work submitted from inside the run, such as
 * {@code AsyncTaskExecutor.submit}, captures the duplicate, so virtual threads and future callbacks see the span.
 *
 * <p>Handlers that should carry the trace id, such as a logging {@code onFailure}, must be part of the future the
 * supplier returns, because the span ends once that future completes:
 * <pre>
 *   traceFuture("name", () -> submit(work).onFailure(log::warn))  // traced
 *   traceFuture("name", () -> submit(work)).onFailure(log::warn)  // runs after the span has ended
 * </pre>
 */
public class BackgroundOperationTracer {

    private final Tracer tracer;
    private final Vertx vertx;

    public BackgroundOperationTracer(OpenTelemetry openTelemetry, Vertx vertx) {
        this.tracer = openTelemetry.getTracer("com.epam.aidial.core.background");
        this.vertx = vertx;
    }

    /**
     * Runs the supplier inside a new root span that stays open until the returned future completes.
     * The supplier runs on the calling thread, so blocking work is not moved onto the event loop.
     */
    public <T> Future<T> traceFuture(String operationName, Supplier<Future<T>> futureSupplier) {
        Span span = tracer.spanBuilder(operationName)
                .setNoParent()
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("operation.type", "background")
                .startSpan();

        ContextInternal own = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        own.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, io.opentelemetry.context.Context.root().with(span));

        Promise<T> result = own.promise();
        // dispatch reports a thrown exception to the context instead of rethrowing it, so it is turned into a failure here
        own.dispatch(() -> {
            try {
                futureSupplier.get().onComplete(result);
            } catch (Throwable e) {
                result.tryFail(e);
            }
        });

        return result.future().onComplete(ar -> {
            if (ar.failed()) {
                span.recordException(ar.cause());
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        });
    }
}
