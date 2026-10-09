package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.controller.ControllerSelector;
import com.epam.aidial.core.server.controller.ControllerTemplate;
import io.vertx.core.Context;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpRequestHead;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.spi.observability.HttpRequest;
import io.vertx.core.spi.tracing.SpanKind;
import io.vertx.core.spi.tracing.TagExtractor;
import io.vertx.core.spi.tracing.VertxTracer;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

public class DialVertxTracer<I, O> implements VertxTracer<I, O> {

    private static final List<String> PATHS = List.of(
            Proxy.HEALTH_CHECK_PATH,
            Proxy.VERSION_PATH
    );

    private final VertxTracer<I, O> delegate;

    public DialVertxTracer(VertxTracer<I, O> delegate) {
        this.delegate = delegate;
    }

    /**
     * Starts the server span on a scratch duplicate of the request's context and copies the span's OTel context onto
     * the request's context itself. The scope the delegate closes in {@code sendResponse} resets only the scratch copy,
     * so the request's context keeps the span after the response and logs written afterwards still carry the trace id.
     * See "Trace context after the response" in docs/tracing.md for the consequences and the design notes.
     */
    @Override
    public <R> I receiveRequest(
            Context context, SpanKind kind, TracingPolicy policy, R request, String operation,
            Iterable<Map.Entry<String, String>> headers, TagExtractor<R> tagExtractor) {

        String spanName = request instanceof HttpServerRequest req ? getServerSpanName(req) : operation;
        Context scratch = ((ContextInternal) context).duplicate();
        I operationState = delegate.receiveRequest(scratch, kind, policy, request, spanName, headers, tagExtractor);
        Object active = scratch.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
        if (active != null) {
            context.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, active);
        }
        return operationState;
    }

    @Override
    public <R> void sendResponse(
            Context context, R response, I payload, Throwable failure, TagExtractor<R> tagExtractor) {

        // the delegate ends the span through the payload and never reads the context, so the scratch duplicate need not be carried here
        delegate.sendResponse(context, response, payload, failure, tagExtractor);
    }

    @Override
    public <R> O sendRequest(
            Context context, SpanKind kind, TracingPolicy policy, R request, String operation,
            BiConsumer<String, String> headers, TagExtractor<R> tagExtractor) {

        String spanName = request instanceof HttpRequest req ? getClientSpanName(req) : operation;
        return delegate.sendRequest(context, kind, policy, request, spanName, headers, tagExtractor);
    }

    @Override
    public <R> void receiveResponse(
            Context context, R response, O payload, Throwable failure, TagExtractor<R> tagExtractor) {

        delegate.receiveResponse(context, response, payload, failure, tagExtractor);
    }

    private String getServerSpanName(HttpServerRequest request) {
        HttpMethod method = request.method();
        String path = request.path();

        if (HttpMethod.GET.equals(method) && PATHS.contains(path)) {
            return "%s %s".formatted(method, path);
        }
        if (HttpMethod.OPTIONS.equals(method)) {
            return method.name();
        }
        ControllerTemplate selection = ControllerSelector.select(request);
        return "%s %s".formatted(method, selection.pathTemplate());
    }

    private String getClientSpanName(HttpRequest request) {
        if (request instanceof HttpRequestHead req && req.traceOperation != null) {
            return req.traceOperation;
        }
        return "%s %s".formatted(request.method(), request.uri());
    }
}
