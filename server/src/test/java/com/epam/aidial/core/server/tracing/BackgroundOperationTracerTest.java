package com.epam.aidial.core.server.tracing;

import com.epam.aidial.core.server.ContextManager;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.log.JsonLogCapture;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.logs.SdkLoggerProvider;
import io.opentelemetry.sdk.logs.data.LogRecordData;
import io.opentelemetry.sdk.logs.export.LogRecordExporter;
import io.opentelemetry.sdk.logs.export.SimpleLogRecordProcessor;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.impl.ContextInternal;
import io.vertx.core.json.JsonObject;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import io.vertx.tracing.opentelemetry.VertxContextStorageProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
        // the SDK default; a sampler that ignores the parent decision would sample these spans, see docs/tracing.md
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setSampler(Sampler.parentBased(Sampler.alwaysOn()))
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
            assertEquals(0, startedSpans.get(), "No span may be recorded under a background run with the default sampler");
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

    @Test
    void testSynchronousExceptionIsLoggedWithItsStackTrace(VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();

        tracer.<Object>traceFuture(() -> {
            throw new IllegalStateException("tracer-test sync boom");
        }).onComplete(ar -> {
            try {
                testContext.verify(() -> {
                    assertTrue(ar.failed());
                    JsonNode attributes = logs.await("Background operation failed synchronously").path("Attributes");
                    assertEquals("java.lang.IllegalStateException", attributes.path("exception.type").asText());
                    assertEquals("tracer-test sync boom", attributes.path("exception.message").asText());
                    assertTrue(attributes.path("exception.stacktrace").asText().contains("testSynchronousExceptionIsLoggedWithItsStackTrace"),
                            "the stack trace must name the throwing code");
                });
                testContext.completeNow();
            } finally {
                logs.close();
            }
        });
    }

    @Test
    void testTraceRunsTheWorkUnderTheRunTrace(VertxTestContext testContext) {
        tracer.trace(() -> testContext.verify(() -> {
            assertTrue(BackgroundOperationTracer.resolveBackgroundTrace().isPresent());
            testContext.completeNow();
        }));
    }

    @Test
    void testTraceLogsFailureOfTheWorkUnderTheRunTrace(VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();

        tracer.trace(() -> {
            throw new IllegalStateException("tracer-test work boom");
        });

        try {
            testContext.verify(() -> {
                JsonNode line = logs.await("Background operation failed synchronously");
                assertEquals("tracer-test work boom", line.path("Attributes").path("exception.message").asText());
                assertEquals(32, line.path("TraceId").asText().length(), "the failure must be attributed to the run");
            });
            testContext.completeNow();
        } finally {
            logs.close();
        }
    }

    @Test
    void testConsoleJsonCarriesTraceIdSpanIdAndFlagsOfTheRun(VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();
        AtomicReference<SpanContext> run = new AtomicReference<>();

        Future<Void> traced = tracer.traceFuture(() -> {
            run.set(Span.current().getSpanContext());
            LOGGER.info("tracer-test console");
            return Future.succeededFuture();
        });

        traced.onComplete(ar -> {
            try {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded());
                    JsonNode line = logs.await("tracer-test console");
                    assertEquals(run.get().getTraceId(), line.path("TraceId").asText());
                    assertEquals(run.get().getSpanId(), line.path("SpanId").asText());
                    assertEquals("00", line.path("TraceFlags").asText());
                });
                testContext.completeNow();
            } finally {
                logs.close();
            }
        });
    }

    @Test
    void testConsoleJsonAndOtelLogRecordsShareTheTrace(VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();
        List<LogRecordData> exported = new CopyOnWriteArrayList<>();
        SdkLoggerProvider loggerProvider = SdkLoggerProvider.builder()
                .addLogRecordProcessor(SimpleLogRecordProcessor.create(new LogRecordExporter() {
                    @Override
                    public CompletableResultCode export(Collection<LogRecordData> records) {
                        exported.addAll(records);
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
                }))
                .build();

        Future<Void> traced = tracer.traceFuture(() -> taskExecutor.<Void>submit(() -> {
            // the OpenTelemetryAppender emits with Context.current(), as done here
            loggerProvider.get("tracer-test").logRecordBuilder().setContext(Context.current()).setBody("tracer-test otel").emit();
            LOGGER.info("tracer-test console and otel");
            return null;
        }));

        traced.onComplete(ar -> {
            try {
                testContext.verify(() -> {
                    assertTrue(ar.succeeded());
                    assertEquals(1, exported.size());
                    SpanContext otel = exported.get(0).getSpanContext();
                    JsonNode line = logs.await("tracer-test console and otel");
                    assertTrue(otel.isValid());
                    assertEquals(otel.getTraceId(), line.path("TraceId").asText());
                    assertEquals(otel.getSpanId(), line.path("SpanId").asText());
                });
                testContext.completeNow();
            } finally {
                logs.close();
            }
        });
    }

    @Test
    void testConsoleJsonOfOverlappingRunsKeepsEachRunsIds(Vertx vertx, VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();
        io.vertx.core.Context parent = vertx.getOrCreateContext();
        parent.runOnContext(v -> {
            Future<SpanContext> a = logFromVirtualThread("tracer-test run-a");
            Future<SpanContext> b = logFromVirtualThread("tracer-test run-b");

            Future.all(a, b).onComplete(ar -> {
                try {
                    testContext.verify(() -> {
                        assertTrue(ar.succeeded());
                        assertNotEquals(a.result().getTraceId(), b.result().getTraceId());
                        for (String name : List.of("run-a", "run-b")) {
                            SpanContext expected = (name.equals("run-a") ? a : b).result();
                            for (JsonNode line : logs.await("tracer-test " + name, 2)) {
                                assertEquals(expected.getTraceId(), line.path("TraceId").asText(), line.path("Body").asText());
                                assertEquals(expected.getSpanId(), line.path("SpanId").asText(), line.path("Body").asText());
                            }
                        }
                    });
                    testContext.completeNow();
                } finally {
                    logs.close();
                }
            });
        });
    }

    private Future<SpanContext> logFromVirtualThread(String message) {
        AtomicReference<SpanContext> run = new AtomicReference<>();
        return tracer.traceFuture(() -> {
            run.set(Span.current().getSpanContext());
            LOGGER.info("{} before", message);
            return taskExecutor.submit(() -> {
                Thread.sleep(100);
                LOGGER.info("{} after", message);
                return null;
            }).map(ignored -> run.get());
        });
    }

    @Test
    void testConsoleJsonIgnoresSpanOfRequestContext(Vertx vertx, VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();
        // what the Vert.x tracer leaves on a request's context before its ProxyContext exists
        SpanContext request = SpanContext.create(
                TraceId.fromLongs(1, 2), "0000000000000003", TraceFlags.getSampled(), TraceState.getDefault());
        ContextInternal requestContext = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        requestContext.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, Context.root().with(Span.wrap(request)));

        requestContext.runOnContext(v -> {
            try {
                assertTrue(BackgroundOperationTracer.resolveBackgroundTrace().isEmpty());
                LOGGER.info("tracer-test request span");
                JsonNode line = logs.await("tracer-test request span");
                testContext.verify(() -> {
                    assertEquals("", line.path("TraceId").asText());
                    assertEquals("", line.path("SpanId").asText());
                    assertEquals("", line.path("TraceFlags").asText());
                });
                testContext.completeNow();
            } catch (Throwable e) {
                testContext.failNow(e);
            } finally {
                logs.close();
            }
        });
    }

    @Test
    void testNoTraceWithoutVertxContextOrActiveContext(Vertx vertx, VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();
        assertNull(Vertx.currentContext());
        assertTrue(BackgroundOperationTracer.resolveBackgroundTrace().isEmpty());
        LOGGER.info("tracer-test no vertx context");

        ContextInternal bare = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        bare.runOnContext(v -> {
            try {
                assertNull(bare.getLocal(VertxContextStorageProvider.ACTIVE_CONTEXT));
                assertTrue(BackgroundOperationTracer.resolveBackgroundTrace().isEmpty());
                LOGGER.info("tracer-test no active context");
                for (String body : List.of("tracer-test no vertx context", "tracer-test no active context")) {
                    JsonNode line = logs.await(body);
                    testContext.verify(() -> {
                        assertEquals("", line.path("TraceId").asText(), body);
                        assertEquals("", line.path("SpanId").asText(), body);
                        assertEquals("", line.path("TraceFlags").asText(), body);
                    });
                }
                testContext.completeNow();
            } catch (Throwable e) {
                testContext.failNow(e);
            } finally {
                logs.close();
            }
        });
    }

    @Test
    void testRunStartedByTimerOfRequestUsesItsOwnTrace(Vertx vertx, VertxTestContext testContext) {
        JsonLogCapture logs = JsonLogCapture.attach();
        SpanContext request = SpanContext.create(
                TraceId.fromLongs(1, 2), "0000000000000003", TraceFlags.getSampled(), TraceState.getDefault());
        ProxyContext proxyContext = mock(ProxyContext.class);
        when(proxyContext.getResponse()).thenReturn(mock(HttpServerResponse.class));
        when(proxyContext.getTraceId()).thenReturn(request.getTraceId());
        when(proxyContext.getSpanId()).thenReturn(request.getSpanId());
        when(proxyContext.getTraceFlags()).thenReturn(request.getTraceFlags().asHex());

        ContextInternal requestContext = ((ContextInternal) vertx.getOrCreateContext()).duplicate();
        requestContext.putLocal(VertxContextStorageProvider.ACTIVE_CONTEXT, Context.root().with(Span.wrap(request)));
        AtomicReference<SpanContext> run = new AtomicReference<>();

        requestContext.runOnContext(v -> {
            ContextManager.setProxyContext(proxyContext);
            // the timer fires on the request's context, as one created in a request handler does
            vertx.setTimer(10, id -> tracer.traceFuture(() -> {
                run.set(BackgroundOperationTracer.resolveBackgroundTrace().orElseThrow());
                LOGGER.info("tracer-test timer of a request");
                return Future.<Void>succeededFuture();
            }).onComplete(ar -> {
                try {
                    testContext.verify(() -> {
                        assertTrue(ar.succeeded());
                        assertNotEquals(request.getTraceId(), run.get().getTraceId());
                        JsonNode line = logs.await("tracer-test timer of a request");
                        assertEquals(run.get().getTraceId(), line.path("TraceId").asText());
                        assertEquals(run.get().getSpanId(), line.path("SpanId").asText());
                    });
                    testContext.completeNow();
                } finally {
                    logs.close();
                }
            }));
        });
    }

    private static String currentTraceId() {
        return Span.current().getSpanContext().getTraceId();
    }
}
