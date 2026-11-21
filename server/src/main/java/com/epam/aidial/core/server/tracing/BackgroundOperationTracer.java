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
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Runs periodic background work under a trace id of its own, so the log lines of one run can be grouped.
 * The design and its limits are described in "Background operations" in docs/tracing.md.
 */
@Slf4j
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
                // nothing subscribes to the returned future of a periodic run, so the failure is logged here
                log.warn("Background operation failed synchronously", e);
                result.tryFail(e);
            }
        });
        return result.future();
    }

    /**
     * Runs the work on the calling thread under a new trace id, for work that completes before it returns.
     */
    public void trace(Runnable work) {
        traceFuture(() -> {
            work.run();
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * The trace of the background run the current Vert.x context belongs to; empty outside a run, including on the
     * context of a request.
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
