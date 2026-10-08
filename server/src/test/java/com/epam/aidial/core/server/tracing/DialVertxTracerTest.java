package com.epam.aidial.core.server.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.http.impl.HttpRequestHead;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.spi.tracing.SpanKind;
import io.vertx.core.spi.tracing.TagExtractor;
import io.vertx.core.spi.tracing.VertxTracer;
import io.vertx.core.tracing.TracingPolicy;
import io.vertx.junit5.Checkpoint;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import io.vertx.tracing.opentelemetry.OpenTelemetryTracingFactory;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(VertxExtension.class)
class DialVertxTracerTest {

    @Mock
    private VertxTracer<?, ?> delegate;
    @InjectMocks
    private DialVertxTracer<?, ?> tracer;
    private Vertx tracedVertx;

    @AfterEach
    void closeTracedVertx() {
        if (tracedVertx != null) {
            tracedVertx.close().toCompletionStage().toCompletableFuture().join();
        }
    }

    @ParameterizedTest
    @MethodSource("receiveRequestDatasource")
    void receiveRequest(HttpMethod method, String path, String expectedName, Vertx vertx) {
        HttpServerRequestInternal request = mock(HttpServerRequestInternal.class);
        Context context = vertx.getOrCreateContext();
        when(request.context()).thenReturn(context);
        when(request.path()).thenReturn(path);
        when(request.method()).thenReturn(method);

        tracer.receiveRequest(request.context(), SpanKind.RPC, null, request, method.name(), null, null);
        // the delegate works on a scratch duplicate of the request's context, so the scope it closes in sendResponse never touches the request's
        ContextInternal root = ((ContextInternal) context).unwrap();
        verify(delegate, only()).receiveRequest(
                argThat(scratch -> scratch instanceof ContextInternal dup && dup.isDuplicate() && dup.unwrap() == root),
                eq(SpanKind.RPC), isNull(), eq(request), eq(expectedName), isNull(), isNull());
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
    void sendResponseKeepsSpanContextOnRequestContext(Vertx vertx) {
        DialVertxTracer<Object, Object> dialTracer = otelTracer();
        Context context = spy(((ContextInternal) vertx.getOrCreateContext()).duplicate());

        Object operation = dialTracer.receiveRequest(context, SpanKind.RPC, TracingPolicy.ALWAYS, "request", "op", List.of(), TagExtractor.empty());
        Span span = Span.fromContext(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
        assertTrue(span.getSpanContext().isValid());

        dialTracer.sendResponse(context, "response", operation, null, TagExtractor.empty());

        assertTrue(((ReadableSpan) span).hasEnded());
        io.opentelemetry.context.Context after = context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
        assertNotNull(after);
        assertEquals(span.getSpanContext(), Span.fromContext(after).getSpanContext());
        // ending the span never takes the context off the request, so no thread on it can see it missing
        verify(context, never()).removeLocal(VertxContextStorageProvider.ACTIVE_CONTEXT);
    }

    @Test
    void receiveRequestParentsSpanOnIncomingTraceparent(Vertx vertx) {
        DialVertxTracer<Object, Object> dialTracer = otelTracer();
        Context context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        String parentSpanId = "00f067aa0ba902b7";
        List<Map.Entry<String, String>> headers = List.of(Map.entry("traceparent", "00-%s-%s-01".formatted(traceId, parentSpanId)));

        Object operation = dialTracer.receiveRequest(context, SpanKind.RPC, TracingPolicy.PROPAGATE, "request", "op", headers, TagExtractor.empty());

        // the scratch duplicate has fresh locals like the per-request context Vert.x hands in, so header parenting still works
        assertNotNull(operation);
        ReadableSpan span = (ReadableSpan) Span.fromContext(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
        assertEquals(traceId, span.getSpanContext().getTraceId());
        assertEquals(parentSpanId, span.getParentSpanContext().getSpanId());
    }

    // IGNORE starts no span; PROPAGATE starts none without an incoming traceparent
    @ParameterizedTest
    @EnumSource(value = TracingPolicy.class, names = {"IGNORE", "PROPAGATE"})
    void receiveRequestWithoutSpanLeavesRequestContextEmpty(TracingPolicy policy, Vertx vertx) {
        DialVertxTracer<Object, Object> dialTracer = otelTracer();
        Context context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        Object operation = dialTracer.receiveRequest(context, SpanKind.RPC, policy, "request", "op", List.of(), TagExtractor.empty());
        assertNull(operation);
        assertNull(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));

        dialTracer.sendResponse(context, "response", operation, null, TagExtractor.empty());
        assertNull(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
    }

    @Test
    void spanStaysCurrentAfterResponseEnd(VertxTestContext testContext) {
        OpenTelemetryOptions options = new OpenTelemetryOptions(OpenTelemetrySdk.builder().build());
        options.setFactory(new DialTracingFactory(options.getFactory()));
        // closed in @AfterEach, once both checkpoints are flagged, so closing cannot race the server-side afterEnd
        tracedVertx = Vertx.vertx(new VertxOptions().setTracingOptions(options));
        Checkpoint afterEnd = testContext.checkpoint();
        Checkpoint responded = testContext.checkpoint();

        tracedVertx.createHttpServer(new HttpServerOptions().setTracingPolicy(TracingPolicy.ALWAYS))
                .requestHandler(request -> {
                    SpanContext during = Span.current().getSpanContext();
                    request.response().end().onComplete(testContext.succeeding(ignored -> testContext.verify(() -> {
                        assertEquals(during, Span.current().getSpanContext());
                        afterEnd.flag();
                    })));
                    testContext.verify(() -> {
                        assertTrue(during.isValid());
                        assertEquals(during, Span.current().getSpanContext());
                    });
                })
                .listen(0)
                .compose(server -> tracedVertx.createHttpClient().request(HttpMethod.GET, server.actualPort(), "localhost", "/v1/bucket"))
                .compose(HttpClientRequest::send)
                .onComplete(testContext.succeeding(response -> responded.flag()));
    }

    @SuppressWarnings("unchecked")
    private static DialVertxTracer<Object, Object> otelTracer() {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        VertxTracer<Object, Object> otel = (VertxTracer<Object, Object>) new OpenTelemetryTracingFactory(sdk).tracer(null);
        return new DialVertxTracer<>(otel);
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
