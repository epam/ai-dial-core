package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.ExportLimits;
import com.epam.aidial.core.server.service.ExportStreamer;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to the bucket locks of an export on every path that ends it before the stream starts; the streaming paths are covered by
 * the API tests.
 */
@ExtendWith(MockitoExtension.class)
public class AdminExportSlotLifecycleTest {

    private static final String BODY = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\"}]}";
    private static final ExportLimits LIMITS = new ExportLimits(100, 500, 10, 10_000, 4 << 20, Duration.ofMinutes(1));

    @Mock
    private ProxyContext context;
    @Mock
    private ConfigAuthorizationService authorizationService;
    @Mock
    private AccessService accessService;
    @Mock
    private AsyncTaskExecutor taskExecutor;
    @Mock
    private Vertx vertx;
    @Mock
    private ExportStreamer streamer;
    @Mock
    private LockService lockService;

    private final AtomicInteger acquired = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();

    @BeforeEach
    void setUp() {
        lenient().when(lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS)).thenAnswer(invocation -> {
            acquired.incrementAndGet();
            return (LockService.Lock) released::incrementAndGet;
        });
        lenient().when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> runInline(invocation.getArgument(0)));
    }

    private Future<Object> runInline(Callable<?> call) {
        try {
            return Future.succeededFuture(call.call());
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    private AdminExportController controller(ExportLimits limits) {
        return new AdminExportController(context, authorizationService, taskExecutor, vertx, Duration.ofSeconds(1),
                accessService, () -> streamer, "test", lockService, limits);
    }

    @Test
    public void testLocksAreReleasedWhenTheCheckOfTheRootsFails() {
        doThrow(new HttpException(HttpStatus.NOT_FOUND, "missing"))
                .doThrow(new HttpException(HttpStatus.FORBIDDEN, "Forbidden"))
                .doThrow(new HttpException(HttpStatus.GATEWAY_TIMEOUT, "The export took too long"))
                .doThrow(new IllegalStateException("failure"))
                .when(streamer).checkRoots(any(), any());

        for (int i = 1; i <= 4; i++) {
            controller(LIMITS).exportSelection(Buffer.buffer(BODY));
            assertEquals(i, acquired.get());
            assertEquals(i, released.get(), "released after check failure " + i);
        }
    }

    @Test
    public void testHeldBucketLockIsConflictBeforeAnythingIsChecked() {
        when(lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS)).thenReturn(null);

        controller(LIMITS).exportSelection(Buffer.buffer(BODY));

        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(context).respond(error.capture(), anyString());
        assertEquals(HttpStatus.CONFLICT, ((HttpException) error.getValue()).getStatus());
        verify(streamer, never()).checkRoots(any(), any());
        assertEquals(0, released.get());
    }

    @Test
    public void testLocksAreReleasedWhenTheHandlerThatStartsTheStreamThrows() {
        // the response of a closed connection throws when the stream starts
        IllegalStateException closed = new IllegalStateException("response is closed");
        when(context.getResponse()).thenThrow(closed);

        controller(LIMITS).exportSelection(Buffer.buffer(BODY));

        assertEquals(1, acquired.get());
        assertEquals(1, released.get(), "a failure of the success handler must not keep the locks until their lease ends");
        verify(context).respond(eq(closed), anyString());
    }

    @Test
    public void testTooManyRootsIsBadRequestBeforeTheLocksAreTaken() {
        ExportLimits oneRoot = new ExportLimits(1, 500, 10, 10_000, 4 << 20, Duration.ofMinutes(1));

        controller(oneRoot).exportSelection(Buffer.buffer("{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\"},"
                + "{\"type\":\"application\",\"url\":\"applications/platform/b\"}]}"));

        ArgumentCaptor<HttpException> error = ArgumentCaptor.forClass(HttpException.class);
        verify(context).respond(error.capture());
        assertEquals(HttpStatus.BAD_REQUEST, error.getValue().getStatus());
        verify(lockService, never()).tryLockBuckets(any());
        verify(taskExecutor, never()).submit(any(Callable.class));
    }
}
