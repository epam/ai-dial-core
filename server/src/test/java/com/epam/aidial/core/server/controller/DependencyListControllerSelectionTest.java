package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.dependency.DependencyWalker;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
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

/** What {@link DependencyListController} does before it reads the body of a request, and when the body cannot be read. */
@ExtendWith(MockitoExtension.class)
public class DependencyListControllerSelectionTest {

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
    private EncryptionService encryptionService;
    @Mock
    private DependencyWalker walker;
    @Mock
    private ApiKeyData apiKeyData;

    private DependencyListController controller;

    @BeforeEach
    void setUp() {
        controller = new DependencyListController(context, authorizationService, taskExecutor, encryptionService, accessService, () -> walker);
        lenient().when(context.getRequest()).thenReturn(httpRequest);
        lenient().when(context.getApiKeyData()).thenReturn(apiKeyData);
    }

    @Test
    public void testBodyThatCannotBeReadIsBadRequestWithoutTheCause() {
        when(httpRequest.body()).thenReturn(Future.failedFuture(new RuntimeException("secret detail")));

        controller.handle();

        verify(context).respond(HttpStatus.BAD_REQUEST, "Failed to read the request body");
        verifyNoInteractions(walker);
    }

    @Test
    public void testPerRequestKeyIsForbiddenBeforeTheBodyIsRead() {
        when(apiKeyData.getPerRequestKey()).thenReturn("per-request-key");

        controller.handle();

        verify(context).respond(HttpStatus.FORBIDDEN, "Forbidden");
        verifyNoInteractions(walker, httpRequest);
    }
}
