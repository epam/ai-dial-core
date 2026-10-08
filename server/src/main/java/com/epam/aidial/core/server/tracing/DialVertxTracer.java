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
     * disconnect) before the OTLP appender reads it. The delegate therefore gets a scratch duplicate that carries the
     * OTel context already on the request's context, if any, so the span is parented exactly as it would be on the
     * request's context itself; the span's OTel context is then copied onto the request's context, where nothing
     * removes it: the request's duplicated context is not shared with other requests and is collected with the request.
     * For what this means for spans started after the response, see "Trace context after the response" in
     * docs/tracing.md.
     */
    @Override
    public <R> I receiveRequest(
            Context context, SpanKind kind, TracingPolicy policy, R request, String operation,
            Iterable<Map.Entry<String, String>> headers, TagExtractor<R> tagExtractor) {

        String spanName = request instanceof HttpServerRequest req ? getServerSpanName(req) : operation;
        Context scratch = ((ContextInternal) context).duplicate();
        Object parent = context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
        if (parent != null) {
            scratch.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, parent);
        }
        I operationState = delegate.receiveRequest(scratch, kind, policy, request, spanName, headers, tagExtractor);
        Object active = scratch.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
        if (active != null && active != parent) {
            context.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, active);
        }
        return operationState;
    }

    /**
     * The request's context is handed through although {@link #receiveRequest} attached the span on the scratch
     * duplicate: the delegate ends the span through the operation state and never reads this context, and the scope
     * it closes is the one bound to the scratch duplicate. A delegate that did consult this context would need the
     * scratch duplicate carried in the operation state instead.
     */
    @Override
    public <R> void sendResponse(
            Context context, R response, I payload, Throwable failure, TagExtractor<R> tagExtractor) {

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
