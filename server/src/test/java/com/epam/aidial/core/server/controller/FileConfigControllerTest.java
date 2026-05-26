package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the narrow startup-race branch: {@link MergedConfigStore#getFileSourcedConfig()}
 * may return {@code null} before {@code init()} completes, and the controller must still dispatch
 * per-shape — a single-entity GET on an unknown name is {@code 404}, not {@code 200 {"items": []}}.
 */
@ExtendWith(MockitoExtension.class)
public class FileConfigControllerTest {

    @Mock
    private ProxyContext context;
    @Mock
    private HttpServerRequest request;
    @Mock
    private ConfigAuthorizationService authorizationService;
    @Mock
    private MergedConfigStore mergedConfigStore;

    @Test
    void singleGetReturns404WhenFileConfigNotYetInitialised() {
        when(context.getRequest()).thenReturn(request);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(authorizationService.isAdmin(context)).thenReturn(true);
        when(mergedConfigStore.getFileSourcedConfig()).thenReturn(null);

        FileConfigController controller = new FileConfigController(
                context, authorizationService, mergedConfigStore, "models", "test-model-v1");
        controller.handle();

        verify(context).respond(eq(HttpStatus.NOT_FOUND));
    }

    @Test
    void listReturnsEmptyEnvelopeWhenFileConfigNotYetInitialised() {
        // Envelope shape must be {"items":[]} — a bare [] array body would break every client.
        when(context.getRequest()).thenReturn(request);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(authorizationService.isAdmin(context)).thenReturn(true);
        when(mergedConfigStore.getFileSourcedConfig()).thenReturn(null);

        FileConfigController controller = new FileConfigController(
                context, authorizationService, mergedConfigStore, "models", null);
        controller.handle();

        ArgumentCaptor<Object> bodyCaptor = ArgumentCaptor.forClass(Object.class);
        verify(context).respond(eq(HttpStatus.OK), bodyCaptor.capture());
        Object body = bodyCaptor.getValue();
        assertInstanceOf(ObjectNode.class, body,
                () -> "Expected {\"items\":[]} envelope, got " + body);
        ObjectNode envelope = (ObjectNode) body;
        assertTrue(envelope.has("items") && envelope.get("items").isArray()
                        && envelope.get("items").isEmpty(),
                () -> "Expected empty items array: " + envelope);
    }
}
