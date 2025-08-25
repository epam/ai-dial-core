package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.argThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApplicationTypeSchemaControllerTest {

    private ProxyContext context;
    private AsyncTaskExecutor taskExecutor;
    private ApplicationTypeSchemaController controller;
    private Config config;

    @BeforeEach
    void setUp() {
        context = mock(ProxyContext.class);
        taskExecutor = mock(AsyncTaskExecutor.class);
        config = mock(Config.class);
        when(context.getProxy()).thenReturn(mock(Proxy.class));
        when(context.getProxy().getTaskExecutor()).thenReturn(taskExecutor);
        when(context.getConfig()).thenReturn(config);
        //noinspection unchecked
        when(taskExecutor.submit(any(Callable.class)))
                .thenAnswer(invocation -> {
                    Callable<?> callable = invocation.getArgument(0);
                    try {
                        return Future.succeededFuture(callable.call());
                    } catch (Exception e) {
                        return Future.failedFuture(e);
                    }
                });
        controller = new ApplicationTypeSchemaController(context);
    }

    @Test
    void handleGetMetaSchema_success() {
        controller.handleGetMetaSchema();
        verify(context).respond(HttpStatus.OK, MetaSchemaHolder.getCustomApplicationMetaSchema());
    }

    @Test
    void handleGetSchema_success() throws Exception {
        final String schemaId = "https://example.com/schema";
        final String schema = "{\"$id\":\"https://example.com/schema\"}";
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.getParam("id")).thenReturn(schemaId);
        Map<String, String> schemas = new HashMap<>();
        schemas.put(schemaId, schema);
        when(config.getApplicationTypeSchemas()).thenReturn(schemas);
        controller.handleGetSchema();
        ObjectNode schemaNode = (ObjectNode) ProxyUtil.MAPPER.readTree(schema);
        verify(context).respond(eq(HttpStatus.OK), eq(schemaNode));
    }

    @Test
    void handleGetSchema_missingId() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.getParam("id")).thenReturn(null);
        controller.handleGetSchema();
        verify(context).respond((Throwable) argThat(exception -> exception instanceof HttpException && ((HttpException) exception).getStatus() == HttpStatus.BAD_REQUEST),
                anyString());
    }

    @Test
    void handleGetSchema_invalidId() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.getParam("id")).thenReturn("invalid uri");
        controller.handleGetSchema();
        verify(context).respond((Throwable) argThat(exception -> exception instanceof HttpException && ((HttpException) exception).getStatus() == HttpStatus.BAD_REQUEST),
                anyString());
    }

    @Test
    void handleGetSchema_notFound() {
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(context.getRequest()).thenReturn(request);
        when(request.getParam("id")).thenReturn("https://example.com/schema");
        when(config.getApplicationTypeSchemas()).thenReturn(new HashMap<>());
        controller.handleGetSchema();
        verify(context).respond((Throwable) argThat(exception -> exception instanceof HttpException && ((HttpException) exception).getStatus() == HttpStatus.NOT_FOUND),
                anyString());
    }

    @Test
    void handleListSchemas_success() throws Exception {
        final String schemaId = "https://example.com/schema";
        final String schema = "{\"$id\":\"https://example.com/schema\",\"dial:applicationTypeEditorUrl\":\"url\",\"dial:applicationTypeDisplayName\":\"name\"}";
        Map<String, String> schemas = new HashMap<>();
        schemas.put(schemaId, schema);
        when(config.getApplicationTypeSchemas()).thenReturn(schemas);
        controller.handleListSchemas();
        ObjectNode schemaNode = (ObjectNode) ProxyUtil.MAPPER.readTree(schema);
        List<JsonNode> schemaList = List.of(schemaNode);
        verify(context).respond(eq(HttpStatus.OK), eq(schemaList));
    }

    @Test
    void handleListSchemas_failure() {
        //noinspection unchecked
        when(taskExecutor.submit(any(Callable.class))).thenReturn(Future.failedFuture(new RuntimeException("error")));
        controller.handleListSchemas();
        verify(context).respond(any(Throwable.class), eq("Failed to read schema from resources"));
    }
}