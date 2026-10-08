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
     * The delegate makes the server span current on the context it is given and returns a scope that the delegate's
     * {@code sendResponse} closes, which resets that context to the parent OTel context. Run on the request's context,
     * that reset would drop the trace id from logs written after the response (late upstream callbacks, client
     * disconnect) before the OTLP appender reads it. Putting the OTel context back after the delegate's
     * {@code sendResponse} is not enough either: a worker thread of the same request can read the root context in
     * between. The delegate therefore gets a scratch duplicate, and the span's OTel context is copied onto the
     * request's context, where nothing removes it: the request's duplicated context is not shared with other requests
     * and is collected with the request. Vert.x hands in a fresh duplicate with empty locals, so the scratch copy loses
     * no parent: the incoming traceparent is the only parent source. For what this means for spans started after the
     * response, see "Trace context after the response" in docs/tracing.md.
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
