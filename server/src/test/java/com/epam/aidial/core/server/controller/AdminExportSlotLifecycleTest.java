package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.ExportArchive.BodyRedactor;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportDiscovery;
import com.epam.aidial.core.server.service.ExportLimits;
import com.epam.aidial.core.server.service.ExportPlanner;
import com.epam.aidial.core.server.service.ExportTestNodes;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
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
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * What happens to the one slot of the selective export on every path that ends an export before the stream starts. The streaming
 * paths are covered by the API tests, which also show that the next export succeeds.
 */
@ExtendWith(MockitoExtension.class)
public class AdminExportSlotLifecycleTest {

    private static final String BODY = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\"}]}";

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
    private ExportPlanner planner;
    @Mock
    private LockService lockService;

    private final AtomicInteger acquired = new AtomicInteger();
    private final AtomicInteger released = new AtomicInteger();

    @BeforeEach
    void setUp() {
        lenient().when(lockService.tryLock(AdminExportController.SELECTION_SLOT)).thenAnswer(invocation -> {
            acquired.incrementAndGet();
            return (LockService.Lock) () -> {
                released.incrementAndGet();
            };
        });
        lenient().when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> runInline(invocation.getArgument(0)));
    }

    /** Runs the task at once, as an executor with a free thread would. */
    private Future<Object> runInline(Callable<?> call) {
        try {
            return Future.succeededFuture(call.call());
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    private AdminExportController controller(@Nullable BodyRedactor redactor) {
        return new AdminExportController(context, authorizationService, taskExecutor, vertx, Duration.ofSeconds(1),
                accessService, () -> planner, redactor, ExportTestNodes.NO_CONTENT, "test", lockService, ExportLimits.defaults());
    }

    private static ResourceDescriptor root() {
        return ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "platform", "platform/", "a");
    }

    private static Closure closureWithOneNode() {
        return new Closure(List.of(ExportTestNodes.node(root(), true, "{}", new ExportDiscovery(List.of(), List.of()))), List.of());
    }

    @Test
    public void testSlotIsReleasedWhenThePlanFailsByLimitTimeoutOrError() {
        when(planner.plan(any(), any(), any(), any()))
                .thenThrow(new HttpException(HttpStatus.GATEWAY_TIMEOUT, "The export took too long"))
                .thenThrow(new HttpException(HttpStatus.REQUEST_ENTITY_TOO_LARGE, "limit"))
                .thenThrow(new IllegalStateException("failure"));

        for (int i = 1; i <= 3; i++) {
            controller(null).exportSelection(Buffer.buffer(BODY));
            assertEquals(i, acquired.get());
            assertEquals(i, released.get(), "released after plan failure " + i);
        }
        verify(context, never()).respond(eq(HttpStatus.SERVICE_UNAVAILABLE), anyString());
    }

    @Test
    public void testFailedPreparationReleasesTheSlot() {
        // a stored path with a .. segment is refused when the archive is prepared, before the head of the response
        ResourceDescriptor unsafe = ResourceDescriptorFactory.fromDecoded(ResourceTypes.APPLICATION, "platform", "platform/", "a/../b");
        when(planner.plan(any(), any(), any(), any())).thenReturn(new Closure(
                List.of(ExportTestNodes.node(unsafe, true, "{}", new ExportDiscovery(List.of(), List.of()))), List.of()));
        BodyRedactor redactor = (identity, body) -> new byte[0];

        controller(redactor).exportSelection(Buffer.buffer(BODY));

        verify(context).respond(any(IllegalStateException.class), anyString());
        assertEquals(1, acquired.get());
        assertEquals(1, released.get());
    }

    @Test
    public void testSlotIsReleasedWhenTheHandlerThatStartsTheStreamThrows() {
        // the response of a closed connection throws when the stream starts
        IllegalStateException closed = new IllegalStateException("response is closed");
        when(context.getResponse()).thenThrow(closed);
        when(planner.plan(any(), any(), any(), any())).thenReturn(closureWithOneNode());
        BodyRedactor redactor = (identity, body) -> new byte[0];

        controller(redactor).exportSelection(Buffer.buffer(BODY));

        assertEquals(1, acquired.get());
        assertEquals(1, released.get(), "a failure of the success handler must not keep the slot until its lease ends");
        verify(context).respond(eq(closed), anyString());
    }

    @Test
    public void testTooManyRootsIsBadRequestBeforeTheSlotIsTaken() {
        ExportLimits oneRoot = new ExportLimits(1, 500, 10, 10_000, 4 * 1024 * 1024, 32 * 1024 * 1024, Duration.ofMinutes(1));
        AdminExportController limited = new AdminExportController(context, authorizationService, taskExecutor, vertx,
                Duration.ofSeconds(1), accessService, () -> planner, null, ExportTestNodes.NO_CONTENT, "test", lockService, oneRoot);

        limited.exportSelection(Buffer.buffer("{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\"},"
                + "{\"type\":\"application\",\"url\":\"applications/platform/b\"}]}"));

        ArgumentCaptor<HttpException> error = ArgumentCaptor.forClass(HttpException.class);
        verify(context).respond(error.capture());
        assertEquals(HttpStatus.BAD_REQUEST, error.getValue().getStatus());
        verify(lockService, never()).tryLock(anyString());
        verify(taskExecutor, never()).submit(any(Callable.class));
    }

}
