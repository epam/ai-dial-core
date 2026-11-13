package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.service.AdminExportSettings;
import com.epam.aidial.core.server.service.ExportStreamer;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** What {@link AdminExportController} does before it reads the body of a request. */
@ExtendWith(MockitoExtension.class)
public class AdminExportControllerSelectionTest {

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
                new AdminExportSettings(), accessService, () -> streamer, "undefined", null);
        lenient().when(context.getRequest()).thenReturn(httpRequest);
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
}
