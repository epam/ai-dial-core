package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.server.log.JsonLogCapture;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(VertxExtension.class)
public class BackgroundOperationTracerTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(BackgroundOperationTracerTest.class);

    private BackgroundOperationTracer tracer;
    private AsyncTaskExecutor taskExecutor;

    @BeforeEach
    void setUp(Vertx vertx) {
        System.setProperty("io.opentelemetry.context.contextStorageProvider",
                "io.vertx.tracing.opentelemetry.VertxContextStorageProvider");

        tracer = new BackgroundOperationTracer(vertx);
        taskExecutor = new AsyncTaskExecutor(vertx, new JsonObject());
    }

    @Test
    void testEachRunGetsItsOwnTraceId(VertxTestContext testContext) {
        AtomicReference<SpanContext> first = new AtomicReference<>();
        AtomicReference<SpanContext> second = new AtomicReference<>();

        Future<Void> one = tracer.traceFuture(() -> {
            first.set(Span.current().getSpanContext());
            return Future.succeededFuture();
        });
        Future<Void> two = one.compose(v -> tracer.traceFuture(() -> {
            second.set(Span.current().getSpanContext());
            return Future.succeededFuture();
        }));

        two.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
            assertTrue(first.get().isValid());
            assertTrue(second.get().isValid());
            assertNotEquals(first.get().getTraceId(), second.get().getTraceId());
            testContext.completeNow();
        })));
    }

    @Test
    void testTraceContextIsNotSampledAndCreatesNoSpans(VertxTestContext testContext) {
        AtomicInteger startedSpans = new AtomicInteger();
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.alwaysOn())
                .addSpanProcessor(new SpanProcessor() {
                    @Override
                    public void onStart(Context parentContext, ReadWriteSpan span) {
                        startedSpans.incrementAndGet();
                    }

                    @Override
                    public boolean isStartRequired() {
                        return true;
                    }

                    @Override
                    public void onEnd(ReadableSpan span) {
                    }

                    @Override
                    public boolean isEndRequired() {
                        return false;
                    }
                })
                .build();
        BlockingCallTracer blockingCallTracer = new BlockingCallTracer(OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build());
        AtomicReference<SpanContext> inside = new AtomicReference<>();
        AtomicReference<Boolean> recording = new AtomicReference<>();

        Future<String> traced = tracer.traceFuture(() -> {
            inside.set(Span.current().getSpanContext());
            recording.set(Span.current().isRecording());
            return Future.succeededFuture(blockingCallTracer.trace("blob.list", () -> "listed"));
        });

        traced.onComplete(testContext.succeeding(result -> testContext.verify(() -> {
            assertEquals("listed", result);
            assertTrue(inside.get().isValid(), "The run must have a trace id for its logs");
            assertFalse(inside.get().isSampled(), "The run must not be sampled");
            assertFalse(recording.get());
            assertEquals(0, startedSpans.get(), "Nothing may start a span under a background run");
            testContext.completeNow();
        })));
    }

    @Test
    void testSharedContextIsNeverWritten(Vertx vertx, VertxTestContext testContext) {
        io.vertx.core.Context parent = vertx.getOrCreateContext();
        parent.runOnContext(v -> runAndCheckSharedContext(parent, false)
                .compose(ignored -> runAndCheckSharedContext(parent, true))
                .onComplete(testContext.succeeding(done -> testContext.completeNow())));
    }

    private Future<Void> runAndCheckSharedContext(io.vertx.core.Context parent, boolean fail) {
        AtomicReference<Object> during = new AtomicReference<>();
        AtomicReference<SpanContext> inside = new AtomicReference<>();
        assertNull(parent.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));

        Future<Void> traced = tracer.traceFuture(() -> {
            during.set(parent.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
            inside.set(Span.current().getSpanContext());
            return fail ? Future.<Void>failedFuture("boom") : Future.<Void>succeededFuture();
        });

        return traced.transform(ar -> {
            assertEquals(fail, ar.failed());
            assertNull(during.get(), "The shared context must not hold the trace context during the run");
            assertTrue(inside.get().isValid(), "The run must still see its own trace context");
            assertNull(parent.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT),
                    "The shared context must not hold the trace context after the run");
            return Future.<Void>succeededFuture();
        });
    }

    @Test
    void testOverlappingRunsKeepTheirOwnTrace(Vertx vertx, VertxTestContext testContext) {
        io.vertx.core.Context parent = vertx.getOrCreateContext();
        parent.runOnContext(v -> {
            Future<List<String>> a = runWithVirtualThread();
            Future<List<String>> b = runWithVirtualThread();

            Future.all(a, b).onComplete(testContext.succeeding(composite -> testContext.verify(() -> {
                List<String> idsA = a.result();
                List<String> idsB = b.result();
                assertEquals(1, new HashSet<>(idsA).size(), "Run A changed trace: " + idsA);
                assertEquals(1, new HashSet<>(idsB).size(), "Run B changed trace: " + idsB);
                assertNotEquals(TraceId.getInvalid(), idsA.get(0));
                assertNotEquals(idsA.get(0), idsB.get(0));
                testContext.completeNow();
            })));
        });
    }

    private Future<List<String>> runWithVirtualThread() {
        List<String> traceIds = new CopyOnWriteArrayList<>();
        return tracer.traceFuture(() -> {
            traceIds.add(currentTraceId());
            return taskExecutor.submit(() -> {
                traceIds.add(currentTraceId());
                Thread.sleep(100);
                traceIds.add(currentTraceId());
                return null;
            }).map(ignored -> {
                traceIds.add(currentTraceId());
                return traceIds;
            });
        });
    }

    @Test
    void testLogsFromWorkerAndHandlersCarryTheTraceId(VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();
        AtomicReference<String> traceId = new AtomicReference<>();

        Future<Object> traced = tracer.traceFuture(() -> {
            traceId.set(currentTraceId());
            return taskExecutor.<Object>submit(() -> {
                LOGGER.info("tracer-test worker");
                throw new IllegalStateException("boom");
            }).onFailure(error -> LOGGER.info("tracer-test inner handler"));
        });
        traced.onFailure(error -> LOGGER.info("tracer-test outer handler"));

        traced.onComplete(ar -> {
            try {
                testContext.verify(() -> {
                    assertTrue(ar.failed());
                    for (String line : List.of("tracer-test worker", "tracer-test inner handler", "tracer-test outer handler")) {
                        assertEquals(traceId.get(), logs.await(line).path("TraceId").asText(), line);
                    }
                });
                testContext.completeNow();
            } finally {
                logs.close();
            }
        });
    }

    @Test
    void testFailureOfTheSuppliedFuturePropagates(VertxTestContext testContext) {
        RuntimeException failure = new RuntimeException("Test failure");

        tracer.<Void>traceFuture(() -> Future.failedFuture(failure))
                .onComplete(testContext.failing(error -> testContext.verify(() -> {
                    assertEquals(failure, error);
                    testContext.completeNow();
                })));
    }

    @Test
    void testSynchronousExceptionFailsTheFuture(VertxTestContext testContext) {
        tracer.<Object>traceFuture(() -> {
            throw new IllegalStateException("boom");
        }).onComplete(testContext.failing(error -> testContext.verify(() -> {
            assertInstanceOf(IllegalStateException.class, error);
            testContext.completeNow();
        })));
    }

    private static String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
