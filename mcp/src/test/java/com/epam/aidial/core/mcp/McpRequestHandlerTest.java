package com.epam.aidial.core.mcp;

import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.Mockito.verify;

class McpRequestHandlerTest {

    @Test
    void respondsWith503StubBody() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        Mockito.when(request.response()).thenReturn(response);
        Mockito.when(response.setStatusCode(Mockito.anyInt())).thenReturn(response);
        Mockito.when(response.putHeader(Mockito.any(CharSequence.class), Mockito.anyString())).thenReturn(response);

        new McpRequestHandler().handle(request);

        verify(response).setStatusCode(503);
        verify(response).putHeader(HttpHeaders.CONTENT_TYPE, "application/json");
        verify(response).end(McpRequestHandler.STUB_BODY);
    }
}
