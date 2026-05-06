package com.epam.aidial.core.mcp.transport;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the no-session error paths in {@link VertxMcpTransportProvider}. The full-stack
 * happy path (initialize, tools/list, delete-then-404) is covered by McpHandshakeTest in :server,
 * which exercises the SDK's session machinery against the real provider.
 */
class VertxMcpTransportProviderTest {

    private Vertx vertx;
    private VertxMcpTransportProvider provider;

    @BeforeEach
    void setUp() {
        vertx = Vertx.vertx();
        provider = new VertxMcpTransportProvider(vertx);
    }

    @AfterEach
    void tearDown() {
        vertx.close();
    }

    @Test
    void getWithoutSessionIdReturns400() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.response()).thenReturn(response);
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(response.setStatusCode(anyInt())).thenReturn(response);

        provider.handleRequest(request);

        verify(response).setStatusCode(400);
        verify(response).end();
    }

    @Test
    void getWithUnknownSessionReturns404() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(request.method()).thenReturn(HttpMethod.GET);
        when(request.response()).thenReturn(response);
        when(request.getHeader("Mcp-Session-Id")).thenReturn("nonexistent");
        when(response.setStatusCode(anyInt())).thenReturn(response);

        provider.handleRequest(request);

        verify(response).setStatusCode(404);
        verify(response).end();
    }

    @Test
    void deleteWithoutSessionIdReturns400() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(request.method()).thenReturn(HttpMethod.DELETE);
        when(request.response()).thenReturn(response);
        when(request.getHeader("Mcp-Session-Id")).thenReturn(null);
        when(response.setStatusCode(anyInt())).thenReturn(response);

        provider.handleRequest(request);

        verify(response).setStatusCode(400);
        verify(response).end();
    }

    @Test
    void deleteWithUnknownSessionReturns404() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(request.method()).thenReturn(HttpMethod.DELETE);
        when(request.response()).thenReturn(response);
        when(request.getHeader("Mcp-Session-Id")).thenReturn("nonexistent");
        when(response.setStatusCode(anyInt())).thenReturn(response);

        provider.handleRequest(request);

        verify(response).setStatusCode(404);
        verify(response).end();
    }

    @Test
    void unsupportedMethodReturns405() {
        HttpServerRequest request = Mockito.mock(HttpServerRequest.class);
        HttpServerResponse response = Mockito.mock(HttpServerResponse.class);
        when(request.method()).thenReturn(HttpMethod.PUT);
        when(request.response()).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);

        provider.handleRequest(request);

        verify(response).setStatusCode(405);
        verify(response).end();
    }
}
