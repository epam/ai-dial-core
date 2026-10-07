package com.epam.aidial.core.storage.tracing;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import lombok.SneakyThrows;

import java.util.Objects;
import java.util.concurrent.Callable;

/**
 * Spans for blocking work (blob storage, Redis, rate limits) running off the event loop.
 *
 * <p>Nesting is tracked in a thread-local instead of being made current in the OpenTelemetry context:
 * core's context storage is the request's Vert.x context, which the event loop shares, so making a span
 * current there from a worker thread could re-parent spans the event loop creates meanwhile.
 * Work outside a traced request is not traced, so background jobs such as resource sync start no root traces.
 */
public class BlockingCallTracer {

    public static final BlockingCallTracer NOOP = new BlockingCallTracer(OpenTelemetry.noop());

    private static final ThreadLocal<Context> ACTIVE = new ThreadLocal<>();

    private final Tracer tracer;

    public BlockingCallTracer(OpenTelemetry openTelemetry) {
        this.tracer = openTelemetry.getTracer("com.epam.aidial.core");
    }

    @SneakyThrows
    public <T> T trace(String name, Callable<T> work) {
        Context previous = ACTIVE.get();
        Context parent = Objects.requireNonNullElseGet(previous, Context::current);
        if (!Span.fromContext(parent).getSpanContext().isValid()) {
            return work.call();
        }

        Span span = tracer.spanBuilder(name).setParent(parent).startSpan();
        ACTIVE.set(parent.with(span));
        try {
            return work.call();
        } catch (Throwable e) {
            span.recordException(e);
            span.setStatus(StatusCode.ERROR);
            throw e;
        } finally {
            if (previous == null) {
                ACTIVE.remove();
            } else {
                ACTIVE.set(previous);
            }
            span.end();
        }
    }

    /**
     * The innermost span {@link #trace} opened on this thread; an invalid span, which ignores attributes, outside one.
     */
    public static Span currentSpan() {
        Context active = ACTIVE.get();
        return (active == null) ? Span.getInvalid() : Span.fromContext(active);
    }
}
