package com.epam.aidial.core.server.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.VertxOptions;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.tracing.opentelemetry.OpenTelemetryOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpResponse;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Runs the tracer on a real Vert.x with the OpenTelemetry SDK, because the unit test of the tracer
 * mocks the context storage this behaviour depends on.
 */
class DialVertxTracerIntegrationTest {

    private static final String NO_TRACEPARENT = "none";

    private final List<SpanData> exportedSpans = new CopyOnWriteArrayList<>();
    private final Map<String, CompletableFuture<String>> traceparentsSeenByUpstream = new ConcurrentHashMap<>();
    private final Map<String, SpanContext> currentSpanOfOutgoingCall = new ConcurrentHashMap<>();

    private Vertx vertx;
    private Vertx upstreamVertx;
    private HttpClient upstreamClient;
    private int serverPort;
    private int upstreamPort;

    @BeforeEach
    void setUp() throws Exception {
        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder()
                        .addSpanProcessor(SimpleSpanProcessor.create(new CollectingSpanExporter()))
                        .build())
                .setPropagators(ContextPropagators.create(W3CTraceContextPropagator.getInstance()))
                .build();
        OpenTelemetryOptions tracingOptions = new OpenTelemetryOptions(sdk);
        tracingOptions.setFactory(new DialTracingFactory(tracingOptions.getFactory()));
        vertx = Vertx.vertx(new VertxOptions().setTracingOptions(tracingOptions));

        // the upstream is not traced: it only reports what it received
        upstreamVertx = Vertx.vertx();
        HttpServer upstream = upstreamVertx.createHttpServer().requestHandler(request -> {
            String call = request.path().substring("/upstream/".length());
            String traceparent = request.getHeader("traceparent");
            traceparentOf(call).complete(traceparent == null ? NO_TRACEPARENT : traceparent);
            request.response().end();
        });
        upstreamPort = upstream.listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).actualPort();

        upstreamClient = vertx.createHttpClient();
        HttpServer server = vertx.createHttpServer().requestHandler(this::handle);
        serverPort = server.listen(0).toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS).actualPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        upstreamVertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void callDuringTheRequestBecomesChildOfTheServerSpan() throws Exception {
        request("before");

        SpanData server = awaitServerSpan();
        SpanData client = clientSpan();
        assertEquals(server.getSpanId(), client.getParentSpanId());
        assertEquals(server.getTraceId(), client.getTraceId());
        String traceparent = traceparentSeenByUpstream("before");
        assertTrue(traceparent.contains(server.getTraceId()), traceparent);
        assertTrue(traceparent.contains(client.getSpanId()), traceparent);
    }

    @Test
    void callAfterTheServerSpanEndedDoesNotBecomeItsChild() throws Exception {
        request("after_end");

        SpanData server = awaitServerSpan();
        // a call parented to the ended span would carry its trace id to the upstream
        assertFalse(traceparentSeenByUpstream("after_end").contains(server.getTraceId()));
    }

    @Test
    void logsAfterTheServerSpanEndedStillSeeItsIds() throws Exception {
        request("after_end");

        SpanData server = awaitServerSpan();
        traceparentSeenByUpstream("after_end");
        SpanContext current = currentSpanOfOutgoingCall.get("after_end");
        assertTrue(current.isValid());
        assertEquals(server.getTraceId(), current.getTraceId());
        assertEquals(server.getSpanId(), current.getSpanId());
    }

    private void handle(HttpServerRequest request) {
        String call = request.path().substring(1);
        if ("before".equals(call)) {
            callUpstream(call).onComplete(ignored -> request.response().end("ok"));
            return;
        }
        request.response().end("ok");
        // the server span ends when the response is sent; the callback runs on the same event loop,
        // so once the span is exported the tracer has finished with the response
        vertx.setPeriodic(10, timerId -> {
            if (exportedSpans.stream().anyMatch(span -> span.getKind() == SpanKind.SERVER)) {
                vertx.cancelTimer(timerId);
                callUpstream(call);
            }
        });
    }

    private Future<Void> callUpstream(String call) {
        currentSpanOfOutgoingCall.put(call, Span.current().getSpanContext());
        return upstreamClient.request(HttpMethod.GET, upstreamPort, "localhost", "/upstream/" + call)
                .compose(HttpClientRequest::send)
                .compose(HttpClientResponse::body)
                .mapEmpty();
    }

    private void request(String call) throws Exception {
        HttpResponse<String> response = java.net.http.HttpClient.newHttpClient().send(
                java.net.http.HttpRequest.newBuilder(URI.create("http://localhost:" + serverPort + "/" + call)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }

    private CompletableFuture<String> traceparentOf(String call) {
        return traceparentsSeenByUpstream.computeIfAbsent(call, key -> new CompletableFuture<>());
    }

    private String traceparentSeenByUpstream(String call) throws Exception {
        return traceparentOf(call).get(10, TimeUnit.SECONDS);
    }

    private SpanData awaitServerSpan() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<SpanData> server = exportedSpans.stream().filter(span -> span.getKind() == SpanKind.SERVER).findFirst();
            if (server.isPresent()) {
                return server.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the server span was not exported: " + exportedSpans);
    }

    private SpanData clientSpan() throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Optional<SpanData> client = exportedSpans.stream().filter(span -> span.getKind() == SpanKind.CLIENT).findFirst();
            if (client.isPresent()) {
                return client.get();
            }
            Thread.sleep(10);
        }
        throw new AssertionError("the client span was not exported: " + exportedSpans);
    }

    private final class CollectingSpanExporter implements SpanExporter {
        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            exportedSpans.addAll(spans);
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
