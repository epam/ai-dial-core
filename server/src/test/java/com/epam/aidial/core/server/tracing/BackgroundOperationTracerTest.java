package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.server.log.JsonLogCapture;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.vertx.core.Context;
import io.vertx.core.Future;
import io.vertx.core.Promise;
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

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder().build();
        OpenTelemetry openTelemetry = OpenTelemetrySdk.builder().setTracerProvider(tracerProvider).build();

        tracer = new BackgroundOperationTracer(openTelemetry, vertx);
        taskExecutor = new AsyncTaskExecutor(vertx, new JsonObject());
    }

    @Test
    void testEachRunIsAnIndependentRootSpan(VertxTestContext testContext) {
        AtomicReference<Span> first = new AtomicReference<>();
        AtomicReference<Span> second = new AtomicReference<>();

        Future<Void> one = tracer.traceFuture("test.one", () -> {
            first.set(Span.current());
            return Future.succeededFuture();
        });
        Future<Void> two = one.compose(v -> tracer.traceFuture("test.two", () -> {
            second.set(Span.current());
            return Future.succeededFuture();
        }));

        two.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
            for (Span span : List.of(first.get(), second.get())) {
                assertTrue(span.getSpanContext().isValid());
                assertFalse(((ReadableSpan) span).getParentSpanContext().isValid(), "Should be a root span");
            }
            assertNotEquals(first.get().getSpanContext().getTraceId(), second.get().getSpanContext().getTraceId());
            testContext.completeNow();
        })));
    }

    @Test
    void testSharedContextIsNeverWritten(Vertx vertx, VertxTestContext testContext) {
        Context parent = vertx.getOrCreateContext();
        parent.runOnContext(v -> runAndCheckSharedContext(parent, false)
                .compose(ignored -> runAndCheckSharedContext(parent, true))
                .onComplete(testContext.succeeding(done -> testContext.completeNow())));
    }

    private Future<Void> runAndCheckSharedContext(Context parent, boolean fail) {
        AtomicReference<Object> during = new AtomicReference<>();
        AtomicReference<Span> inside = new AtomicReference<>();
        assertNull(parent.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));

        Future<Void> traced = tracer.traceFuture("test.operation", () -> {
            during.set(parent.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
            inside.set(Span.current());
            return fail ? Future.<Void>failedFuture("boom") : Future.<Void>succeededFuture();
        });

        return traced.transform(ar -> {
            assertEquals(fail, ar.failed());
            assertNull(during.get(), "The shared context must not hold the span during the run");
            assertTrue(inside.get().getSpanContext().isValid(), "The run must still see its own span");
            assertNull(parent.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT),
                    "The shared context must not hold the span after the run");
            return Future.<Void>succeededFuture();
        });
    }

    @Test
    void testOverlappingRunsKeepTheirOwnTrace(Vertx vertx, VertxTestContext testContext) {
        Context parent = vertx.getOrCreateContext();
        parent.runOnContext(v -> {
            Future<List<String>> a = runWithVirtualThread("test.a");
            Future<List<String>> b = runWithVirtualThread("test.b");

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

    private Future<List<String>> runWithVirtualThread(String name) {
        List<String> traceIds = new CopyOnWriteArrayList<>();
        return tracer.traceFuture(name, () -> {
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

        Future<Object> traced = tracer.traceFuture("test.logs", () -> {
            traceId.set(currentTraceId());
            return taskExecutor.<Object>submit(() -> {
                LOGGER.info("tracer-test worker");
                throw new IllegalStateException("boom");
            }).onFailure(error -> LOGGER.info("tracer-test failure handler"));
        });

        traced.onComplete(ar -> {
            try {
                testContext.verify(() -> {
                    assertTrue(ar.failed());
                    assertEquals(traceId.get(), logs.await("tracer-test worker").path("TraceId").asText());
                    assertEquals(traceId.get(), logs.await("tracer-test failure handler").path("TraceId").asText());
                });
                testContext.completeNow();
            } finally {
                logs.close();
            }
        });
    }

    @Test
    void testSpanEndsWhenFutureCompletes(VertxTestContext testContext) {
        Promise<Void> work = Promise.promise();
        AtomicReference<Span> span = new AtomicReference<>();

        Future<Void> traced = tracer.traceFuture("test.operation", () -> {
            span.set(Span.current());
            return work.future();
        });

        assertFalse(((ReadableSpan) span.get()).hasEnded(), "Span must stay open while the work is running");
        work.complete();

        traced.onComplete(testContext.succeeding(v -> testContext.verify(() -> {
            ReadableSpan ended = (ReadableSpan) span.get();
            assertTrue(ended.hasEnded());
            assertNotEquals(StatusCode.ERROR, ended.toSpanData().getStatus().getStatusCode());
            testContext.completeNow();
        })));
    }

    @Test
    void testFailureIsRecorded(VertxTestContext testContext) {
        RuntimeException failure = new RuntimeException("Test failure");
        AtomicReference<Span> span = new AtomicReference<>();

        tracer.<Void>traceFuture("test.operation", () -> {
            span.set(Span.current());
            return Future.failedFuture(failure);
        }).onComplete(testContext.failing(error -> testContext.verify(() -> {
            assertEquals(failure, error);
            ReadableSpan ended = (ReadableSpan) span.get();
            assertTrue(ended.hasEnded());
            assertEquals(StatusCode.ERROR, ended.toSpanData().getStatus().getStatusCode());
            testContext.completeNow();
        })));
    }

    @Test
    void testSynchronousExceptionFailsTheFutureAndEndsTheSpan(VertxTestContext testContext) {
        AtomicReference<Span> span = new AtomicReference<>();

        tracer.<Object>traceFuture("test.operation", () -> {
            span.set(Span.current());
            throw new IllegalStateException("boom");
        }).onComplete(testContext.failing(error -> testContext.verify(() -> {
            assertInstanceOf(IllegalStateException.class, error);
            ReadableSpan ended = (ReadableSpan) span.get();
            assertTrue(ended.hasEnded());
            assertEquals(StatusCode.ERROR, ended.toSpanData().getStatus().getStatusCode());
            testContext.completeNow();
        })));
    }

    private static String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
