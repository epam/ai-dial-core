package com.epam.aidial.core.server.tracing;

import io.vertx.core.spi.VertxTracerFactory;
import io.vertx.core.spi.tracing.VertxTracer;
import io.vertx.core.tracing.TracingOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;

public class DialTracingFactory extends OpenTelemetryTracingFactory {

    private final VertxTracerFactory delegate;

    public DialTracingFactory(VertxTracerFactory delegate) {
        this.delegate = delegate;
    }

    @Override
    public VertxTracer<?, ?> tracer(TracingOptions options) {
        return new DialVertxTracer<>(delegate.tracer(options));
    }
}
