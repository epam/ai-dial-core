package com.epam.aidial.core.server.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.only;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@ExtendWith(VertxExtension.class)
class DialVertxTracerTest {

    static {
        // as AiDial.start(): OTel's Context.current() reads the request's Vert.x context
        System.setProperty("io.opentelemetry.context.contextStorageProvider", "io.vertx.tracing.opentelemetry.VertxContextStorageProvider");
    }

    @Mock
    private VertxTracer<?, ?> delegate;
    @InjectMocks
    private DialVertxTracer<?, ?> tracer;

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
    @SuppressWarnings("unchecked")
    void sendResponseKeepsSpanContextOnRequestContext(Vertx vertx) {
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder().build();
        VertxTracer<Object, Object> otelTracer = (VertxTracer<Object, Object>) new OpenTelemetryTracingFactory(openTelemetry).tracer(null);
        DialVertxTracer<Object, Object> dialTracer = new DialVertxTracer<>(otelTracer);
        Context context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        Object operation = dialTracer.receiveRequest(context, SpanKind.RPC, TracingPolicy.ALWAYS, "request", "op", List.of(), TagExtractor.empty());
        Span span = Span.fromContext(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
        assertTrue(span.getSpanContext().isValid());

        dialTracer.sendResponse(context, "response", operation, null, TagExtractor.empty());

        assertTrue(((ReadableSpan) span).hasEnded());
        Span after = Span.fromContext(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
        assertEquals(span.getSpanContext(), after.getSpanContext());
    }

    @Test
    void sendResponseWithoutSpanContextLeavesRequestContextEmpty(Vertx vertx) {
        Context context = ((ContextInternal) vertx.getOrCreateContext()).duplicate();

        tracer.sendResponse(context, null, null, null, null);

        verify(delegate, only()).sendResponse(context, null, null, null, null);
        assertNull(context.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
    }

    @Test
    void spanStaysCurrentAfterResponseEnd(VertxTestContext testContext) {
        OpenTelemetryOptions options = new OpenTelemetryOptions(OpenTelemetrySdk.builder().build());
        options.setFactory(new DialTracingFactory(options.getFactory()));
        Vertx vertx = Vertx.vertx(new VertxOptions().setTracingOptions(options));
        Checkpoint afterEnd = testContext.checkpoint();
        Checkpoint responded = testContext.checkpoint();

        vertx.createHttpServer(new HttpServerOptions().setTracingPolicy(TracingPolicy.ALWAYS))
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
                .compose(server -> vertx.createHttpClient().request(HttpMethod.GET, server.actualPort(), "localhost", "/v1/bucket"))
                .compose(HttpClientRequest::send)
                .onComplete(testContext.succeeding(response -> {
                    responded.flag();
                    vertx.close();
                }));
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
