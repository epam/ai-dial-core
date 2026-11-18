package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.DeploymentData;
import com.epam.aidial.core.server.data.ModelData;
import com.epam.aidial.core.server.util.FieldSelection;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

public class DeploymentControllerTest {

    private final Proxy proxy = mock(Proxy.class);
    private final ProxyContext context = mock(ProxyContext.class);
    private final AsyncTaskExecutor executor = mock(AsyncTaskExecutor.class);

    private DeploymentController controller;

    @BeforeEach
    void setUp() {
        when(proxy.getTaskExecutor()).thenReturn(executor);
        controller = new DeploymentController(proxy, context);
    }

    @Test
    void selectedFieldsAreProjectedOnTheExecutor() throws Exception {
        when(executor.submit(any(Callable.class))).thenAnswer(invocation ->
                Future.succeededFuture(((Callable<?>) invocation.getArgument(0)).call()));
        ModelData model = new ModelData();
        model.setId("m");
        model.setDisplayVersion("1.0");

        controller.respondListing(List.of(model), FieldSelection.parse("display_version", Set.of("id", "object")));

        ArgumentCaptor<Object> body = ArgumentCaptor.forClass(Object.class);
        verify(context).respond(eq(HttpStatus.OK), body.capture());
        JsonNode expected = ProxyUtil.MAPPER.readTree("[{\"id\": \"m\", \"object\": \"model\", \"display_version\": \"1.0\"}]");
        assertEquals(expected, body.getValue());
        verifyNoMoreInteractions(context);
    }

    @Test
    void failedProjectionAnswersInternalError() {
        when(executor.submit(any(Callable.class))).thenReturn(Future.failedFuture(new IllegalStateException("boom")));

        controller.respondListing(List.of(new ModelData()), FieldSelection.parse("display_name", Set.of("id")));

        verify(context).respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
        verifyNoMoreInteractions(context);
    }

    @Test
    void failedWriteOfTheFullListingAnswersInternalError() {
        List<DeploymentData> deployments = List.of(new ModelData());
        doThrow(new IllegalStateException("boom")).when(context).respond(eq(HttpStatus.OK), any(Object.class));

        controller.respondListing(deployments, null);

        verify(context).respond(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error");
    }
}
