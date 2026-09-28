package com.epam.aidial.core.server.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.impl.HttpRequestHead;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.spi.tracing.SpanKind;
import io.vertx.core.spi.tracing.VertxTracer;
import io.vertx.junit5.VertxExtension;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(VertxExtension.class)
class DialVertxTracerTest {

    @Mock
    private VertxTracer<Object, Object> delegate;
    @InjectMocks
    private DialVertxTracer<Object, Object> tracer;

    @ParameterizedTest
    @MethodSource("receiveRequestDatasource")
    void receiveRequest(HttpMethod method, String path, String expectedName, Vertx vertx) {
        HttpServerRequestInternal request = mock(HttpServerRequestInternal.class);
        when(request.context()).thenReturn(vertx.getOrCreateContext());
        when(request.path()).thenReturn(path);
        when(request.method()).thenReturn(method);

        tracer.receiveRequest(request.context(), SpanKind.RPC, null, request, request.method().name(), null, null);
        verify(delegate, only()).receiveRequest(request.context(), SpanKind.RPC, null, request, expectedName, null, null);
    }

    @ParameterizedTest
    @MethodSource("sendRequestDatasource")
    void sendRequest(HttpMethod method, String path, String traceOperation, String expectedName, Vertx vertx) {
        HttpRequestHead request = new HttpRequestHead(
                method, path, null, null, null, traceOperation);

        Context context = vertx.getOrCreateContext();
        tracer.sendRequest(context, SpanKind.RPC, null, request, request.method().name(), null, null);
        verify(delegate, only()).sendRequest(context, SpanKind.RPC, null, request, expectedName, null, null);
    }

    @Test
    void sendResponseKeepsSpanIdsAfterDelegateEndsTheSpan(Vertx vertx) {
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        SpanContext span = SpanContext.create("22510e56eb9b21f6b03dbc038cd8fb71", "b03dbc038cd8fb71",
                TraceFlags.getSampled(), TraceState.getDefault());
        context.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, io.opentelemetry.context.Context.root().with(Span.wrap(span)));
        // like OpenTelemetryTracer: ending the span closes its scope, which removes it from the request context
        doAnswer(invocation -> context.removeLocal(VertxContextStorageProvider.ACTIVE_CONTEXT))
                .when(delegate).sendResponse(any(), any(), any(), any(), any());

        tracer.sendResponse(context, null, new Object(), null, null);

        io.opentelemetry.context.Context active = context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
        assertEquals(span, Span.fromContext(active).getSpanContext());
    }

    @Test
    void sendResponseKeepsOuterContextRestoredByDelegate(Vertx vertx) {
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        io.opentelemetry.context.Context outer = io.opentelemetry.context.Context.root().with(Span.wrap(SpanContext.create(
                "22510e56eb9b21f6b03dbc038cd8fb71", "b03dbc038cd8fb71", TraceFlags.getSampled(), TraceState.getDefault())));
        io.opentelemetry.context.Context inner = outer.with(Span.wrap(SpanContext.create(
                "22510e56eb9b21f6b03dbc038cd8fb71", "038cd8fb71b03dbc", TraceFlags.getSampled(), TraceState.getDefault())));
        context.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, inner);
        // a nested span on the same context: closing its scope restores the outer, still live span
        doAnswer(invocation -> {
            context.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, outer);
            return null;
        }).when(delegate).sendResponse(any(), any(), any(), any(), any());

        tracer.sendResponse(context, null, new Object(), null, null);

        assertSame(outer, context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
    }

    @Test
    void sendResponseWithoutSpanLeavesContextAlone(Vertx vertx) {
        ContextInternal context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        io.opentelemetry.context.Context active = io.opentelemetry.context.Context.root().with(Span.wrap(SpanContext.create(
                "22510e56eb9b21f6b03dbc038cd8fb71", "b03dbc038cd8fb71", TraceFlags.getSampled(), TraceState.getDefault())));
        context.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, active);

        // TracingPolicy.IGNORE: the delegate returned no payload from receiveRequest and owns no span
        tracer.sendResponse(context, null, null, null, null);

        assertSame(active, context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
    }

    @Test
    void sendResponseLeavesSharedEventLoopContextAlone(Vertx vertx) {
        // not a duplicate: every request on this loop shares it, so a restored span would leak into the next request
        ContextInternal context = (ContextInternal) vertx.getOrCreateContext();
        io.opentelemetry.context.Context active = io.opentelemetry.context.Context.root().with(Span.wrap(SpanContext.create(
                "22510e56eb9b21f6b03dbc038cd8fb71", "b03dbc038cd8fb71", TraceFlags.getSampled(), TraceState.getDefault())));
        context.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, active);
        doAnswer(invocation -> context.removeLocal(VertxContextStorageProvider.ACTIVE_CONTEXT))
                .when(delegate).sendResponse(any(), any(), any(), any(), any());

        tracer.sendResponse(context, null, new Object(), null, null);

        assertNull(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
    }

    public static List<Arguments> receiveRequestDatasource() {
        return List.of(
                Arguments.of(HttpMethod.POST, "/openai/deployments/llm/chat/completions", "POST /openai/deployments/{id}/chat/completions"),
                Arguments.of(HttpMethod.GET, "/v1/bucket", "GET /v1/bucket"),
                Arguments.of(HttpMethod.GET, "/health", "GET /health"),
                Arguments.of(HttpMethod.GET, "/version", "GET /version"),
                Arguments.of(HttpMethod.POST, "/route/path", "POST /{path}"),
                Arguments.of(HttpMethod.POST, "/fake", "POST /{path}"),
                Arguments.of(HttpMethod.OPTIONS, "/openai/deployments/llm/chat/completions", "OPTIONS")
        );
    }

    public static List<Arguments> sendRequestDatasource() {
        return List.of(
                Arguments.of(HttpMethod.GET, "/v1/bucket", null, "GET /v1/bucket"),
                Arguments.of(HttpMethod.GET, "/v1/bucket", "op", "op")
        );
    }
}
