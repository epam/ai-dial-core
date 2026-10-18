package com.epam.aidial.core.server.tracing;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.impl.ContextInternal;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Gives every run of a periodic background operation its own trace id, so the log lines of the run, the OTel log
 * records included, can be told apart and grouped. It records no spans: the trace context of a run is not sampled,
 * so nothing is exported as a trace and the storage calls of the run create no child spans
 * (see {@code BlockingCallTracer}).
 *
 * <p>Each run gets its own duplicated Vert.x context and the trace context is kept only in that duplicate's locals,
 * the way every HTTP request keeps its own. The context a timer fires on is shared by all runs and by whatever else
 * uses it, so nothing is written to it and there is nothing to restore. Work submitted from inside the run, such as
 * {@code AsyncTaskExecutor.submit}, captures the duplicate, and so do the callbacks of the returned future, so
 * virtual threads and handlers log with the run's trace id.
 *
 * <p>The OTel log records read the trace context from the Vert.x context storage. The console JSON layout, which
 * takes the trace of a request from its {@code ProxyContext}, reads it with {@link #resolveBackgroundTrace()}, which accepts only
 * the {@link BackgroundSpan} stored here and so never the span of a request.
 */
public class BackgroundOperationTracer {

    private static final IdGenerator ID_GENERATOR = IdGenerator.random();

    private final Vertx vertx;

    public BackgroundOperationTracer(Vertx vertx) {
        this.vertx = vertx;
    }

    /**
     * Runs the supplier on the calling thread under a new trace id, so blocking work is not moved onto the event loop.
     * Callbacks of the returned future run under the same trace id.
     */
    public <T> Future<T> traceFuture(Supplier<Future<T>> futureSupplier) {
        SpanContext traceContext = SpanContext.create(
                ID_GENERATOR.generateTraceId(), ID_GENERATOR.generateSpanId(), TraceFlags.getDefault(), TraceState.getDefault());

        ContextInternal own = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        own.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT,
                io.opentelemetry.context.Context.root().with(new BackgroundSpan(traceContext)));

        Promise<T> result = own.promise();
        // dispatch reports a thrown exception to the context instead of rethrowing it, so it is turned into a failure here
        own.dispatch(() -> {
            try {
                futureSupplier.get().onComplete(result);
            } catch (Throwable e) {
                result.tryFail(e);
            }
        });
        return result.future();
    }

    /**
     * The trace context of the background run the current Vert.x context belongs to, empty outside a run: without a
     * Vert.x context, without an active OTel context, or when the active span is not a run's {@link BackgroundSpan},
     * such as the span of a request.
     */
    public static Optional<SpanContext> resolveBackgroundTrace() {
        io.vertx.core.Context vertxContext = Vertx.currentContext();
        if (vertxContext == null) {
            return Optional.empty();
        }
        Object active = vertxContext.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
        if (active instanceof io.opentelemetry.context.Context otelContext
                && Span.fromContextOrNull(otelContext) instanceof BackgroundSpan span) {
            return Optional.of(span.spanContext);
        }
        return Optional.empty();
    }

    /**
     * A non-recording span that only carries the trace context of a run, like {@code Span.wrap}, but recognizable:
     * {@code Span.wrap} is also what the SDK returns for the unsampled span of a request.
     */
    private static final class BackgroundSpan implements Span {

        private final SpanContext spanContext;

        private BackgroundSpan(SpanContext spanContext) {
            this.spanContext = spanContext;
        }

        @Override
        public <T> Span setAttribute(AttributeKey<T> key, T value) {
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes) {
            return this;
        }

        @Override
        public Span addEvent(String name, Attributes attributes, long timestamp, TimeUnit unit) {
            return this;
        }

        @Override
        public Span setStatus(StatusCode statusCode, String description) {
            return this;
        }

        @Override
        public Span recordException(Throwable exception, Attributes additionalAttributes) {
            return this;
        }

        @Override
        public Span updateName(String name) {
            return this;
        }

        @Override
        public void end() {
        }

        @Override
        public void end(long timestamp, TimeUnit unit) {
        }

        @Override
        public SpanContext getSpanContext() {
            return spanContext;
        }

        @Override
        public boolean isRecording() {
            return false;
        }
    }
}
