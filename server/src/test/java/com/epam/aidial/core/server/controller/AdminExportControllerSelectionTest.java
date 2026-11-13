package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.ExportLimits;
import com.epam.aidial.core.server.service.ExportRequestParser.ExportRequest;
import com.epam.aidial.core.server.service.ExportStreamer;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What {@link AdminExportController} does with a request body before the response starts. */
@ExtendWith(MockitoExtension.class)
public class AdminExportControllerSelectionTest {

    private static final String VALID_BODY = "{\"roots\":[{\"type\":\"application\",\"url\":\"applications/platform/a\"}]}";

    @Mock
    private ProxyContext context;
    @Mock
    private HttpServerRequest httpRequest;
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

    private AdminExportController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminExportController(context, authorizationService, taskExecutor, vertx,
                Duration.ofSeconds(1), accessService, () -> streamer, "undefined", null, new ExportLimits(100, 500, 10, 10_000, 4 << 20,
                        Duration.ofMinutes(1)));
        lenient().when(context.getRequest()).thenReturn(httpRequest);
        lenient().when(taskExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            try {
                return Future.succeededFuture(((Callable<?>) invocation.getArgument(0)).call());
            } catch (Exception e) {
                return Future.failedFuture(e);
            }
        });
    }

    @Test
    public void testBodyThatCannotBeReadIsBadRequestWithoutTheCause() {
        when(authorizationService.isAdmin(context)).thenReturn(true);
        when(httpRequest.body()).thenReturn(Future.failedFuture(new RuntimeException("secret detail")));

        controller.handle();

        verify(context).respond(HttpStatus.BAD_REQUEST, "Failed to read the request body");
        verifyNoInteractions(streamer);
    }

    @Test
    public void testNonAdminIsForbiddenBeforeTheBodyIsRead() {
        when(authorizationService.isAdmin(context)).thenReturn(false);

        controller.handle();

        verify(context).respond(HttpStatus.FORBIDDEN, "Forbidden");
        verifyNoInteractions(streamer, httpRequest);
    }

    @Test
    public void testRootsAreCheckedBeforeTheResponseWithThePublicReadPredicateOfTheCaller() {
        controller.exportSelection(Buffer.buffer(VALID_BODY));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<ResourceDescriptor>> predicate = ArgumentCaptor.forClass(Predicate.class);
        ArgumentCaptor<ExportRequest> request = ArgumentCaptor.forClass(ExportRequest.class);
        verify(streamer).checkRoots(request.capture(), predicate.capture());
        assertEquals(1, request.getValue().roots().size());
        ResourceDescriptor file = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", "icons/a.png");

        when(accessService.lookupPermissions(Set.of(file), context)).thenReturn(Map.of(file, Set.of(ResourceAccessType.READ)));
        assertTrue(predicate.getValue().test(file));

        when(accessService.lookupPermissions(Set.of(file), context)).thenReturn(Map.of(file, Set.of(ResourceAccessType.WRITE)));
        assertFalse(predicate.getValue().test(file), "a resource that the check does not allow to read is not read");

        when(accessService.lookupPermissions(Set.of(file), context)).thenReturn(Map.of());
        assertFalse(predicate.getValue().test(file));
    }
}
