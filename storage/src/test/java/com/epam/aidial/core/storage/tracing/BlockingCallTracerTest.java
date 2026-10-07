package com.epam.aidial.core.storage.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockingCallTracerTest {

    private InMemorySpanExporter exporter;
    private OpenTelemetrySdk openTelemetry;
    private BlockingCallTracer tracing;

    @BeforeEach
    void setUp() {
        exporter = InMemorySpanExporter.create();
        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build())
                .build();
        tracing = new BlockingCallTracer(openTelemetry);
    }

    @AfterEach
    void tearDown() {
        openTelemetry.close();
    }

    @Test
    void testNoSpanOutsideTracedRequest() {
        String result = tracing.trace("blob.load", () -> {
            assertFalse(BlockingCallTracer.currentSpan().getSpanContext().isValid());
            return "value";
        });

        assertEquals("value", result);
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    void testNestedSpansUnderRequestSpan() {
        Span request = openTelemetry.getTracer("test").spanBuilder("request").startSpan();
        try (Scope ignore = request.makeCurrent()) {
            tracing.trace("resource.get", () -> {
                BlockingCallTracer.currentSpan().setAttribute("dial.cache.hit", false);
                return tracing.trace("blob.load", () -> null);
            });
        } finally {
            request.end();
        }

        List<SpanData> spans = exporter.getFinishedSpanItems();
        SpanData blob = find(spans, "blob.load");
        SpanData resource = find(spans, "resource.get");
        assertEquals(resource.getSpanId(), blob.getParentSpanId());
        assertEquals(request.getSpanContext().getSpanId(), resource.getParentSpanId());
        assertEquals(false, resource.getAttributes().asMap().values().iterator().next());
        assertFalse(BlockingCallTracer.currentSpan().getSpanContext().isValid());
    }

    @Test
    void testFailureMarksSpanAndRethrows() {
        IllegalStateException error = new IllegalStateException("boom");
        Span request = openTelemetry.getTracer("test").spanBuilder("request").startSpan();
        try (Scope ignore = request.makeCurrent()) {
            IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> tracing.trace("blob.store", () -> {
                throw error;
            }));
            assertSame(error, thrown);
        } finally {
            request.end();
        }

        SpanData blob = find(exporter.getFinishedSpanItems(), "blob.store");
        assertEquals(StatusCode.ERROR, blob.getStatus().getStatusCode());
        assertEquals(1, blob.getEvents().size());
        assertFalse(BlockingCallTracer.currentSpan().getSpanContext().isValid());
    }

    private static SpanData find(List<SpanData> spans, String name) {
        return spans.stream().filter(span -> span.getName().equals(name)).findFirst().orElseThrow();
    }
}
