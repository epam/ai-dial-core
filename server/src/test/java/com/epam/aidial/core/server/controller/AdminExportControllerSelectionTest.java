package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.ExportClosureWalker.Closure;
import com.epam.aidial.core.server.service.ExportLimits;
import com.epam.aidial.core.server.service.ExportPlanner;
import com.epam.aidial.core.server.service.ExportTestNodes;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The selective mode of {@link AdminExportController}: what the existing export endpoint does with a request body.
 */
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
    private ExportPlanner planner;

    private AdminExportController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminExportController(context, authorizationService, taskExecutor, vertx,
                Duration.ofSeconds(1), accessService, () -> planner, null, ExportTestNodes.NO_CONTENT, "undefined", null,
                ExportLimits.defaults());
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
        verifyNoInteractions(planner);
    }

    @Test
    public void testPublicReadPredicateAppliesToFileLikeToApplication() {
        when(planner.plan(any(), any(), any(), any())).thenReturn(new Closure(List.of(), List.of()));
        controller.exportSelection(Buffer.buffer(VALID_BODY));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Predicate<ResourceDescriptor>> predicate = ArgumentCaptor.forClass(Predicate.class);
        verify(planner).plan(any(), predicate.capture(), any(), any());
        ResourceDescriptor file = ResourceDescriptorFactory.fromDecoded(ResourceTypes.FILE, "public", "public/", "icons/a.png");

        when(accessService.lookupPermissions(Set.of(file), context)).thenReturn(Map.of(file, Set.of(ResourceAccessType.READ)));
        assertTrue(predicate.getValue().test(file));

        when(accessService.lookupPermissions(Set.of(file), context)).thenReturn(Map.of(file, Set.of(ResourceAccessType.WRITE)));
        assertFalse(predicate.getValue().test(file), "a File that the check does not allow to read is not read");

        when(accessService.lookupPermissions(Set.of(file), context)).thenReturn(Map.of());
        assertFalse(predicate.getValue().test(file));
    }
}
