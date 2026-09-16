package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.service.metadata.HttpHeadersHandler;
import com.epam.aidial.core.storage.http.HttpException;
import com.sun.net.httpserver.HttpServer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.ByteArrayOutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAuthorizationClientTest {

    private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (k, v) -> true);

    @Mock
    private HttpClient httpClientMock;

    @Mock
    private HttpHeadersHandler httpHeadersHandler;

    @InjectMocks
    private ResourceAuthorizationClient resourceAuthorizationClient;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    @Test
    void testExecuteGet_Success() throws Exception {
        // Given
        String url = "https://example.com/resource";
        String jsonResponse = "{\"key\":\"value\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        TestResponse expectedResponse = new TestResponse("value");
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(jsonResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(EMPTY_HEADERS);

        // When
        TestResponse actualResponse = resourceAuthorizationClient.executeGet(url, TestResponse.class);

        // Then
        assertNotNull(actualResponse);
        assertEquals(expectedResponse.getKey(), actualResponse.getKey());
    }

    @Test
    void testExecuteGet_NotFoundStatus() throws Exception {
        // Given
        String url = "https://example.com/resource";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        HttpHeaders httpHeadersMock = mock(HttpHeaders.class);
        when(httpHeadersMock.map()).thenReturn(new HashMap<>());
        when(httpResponseMock.headers()).thenReturn(httpHeadersMock);
        when(httpHeadersHandler.convertHttpHeadersToMap(httpHeadersMock)).thenReturn(new HashMap<>());
        when(httpResponseMock.statusCode()).thenReturn(404);

        // When
        HttpException exception = assertThrows(HttpException.class, () -> resourceAuthorizationClient.executeGet(url, TestResponse.class));

        //Then
        assertEquals(404, exception.getStatus().getCode());
        assertEquals("Authorization server returns error code", exception.getMessage());
    }

    @Test
    void testExecuteGet_UnauthorizedStatus() throws Exception {
        // Given
        String url = "https://example.com/resource";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(401);
        HttpHeaders httpHeadersMock = mock(HttpHeaders.class);
        when(httpHeadersMock.map()).thenReturn(new HashMap<>());
        when(httpResponseMock.headers()).thenReturn(httpHeadersMock);
        when(httpHeadersHandler.convertHttpHeadersToMap(httpHeadersMock)).thenReturn(new HashMap<>());

        // When
        HttpException exception = assertThrows(HttpException.class, () -> resourceAuthorizationClient.executeGet(url, TestResponse.class));

        //Then
        assertEquals(401, exception.getStatus().getCode());
        assertEquals("Authorization server returns 401 error code", exception.getMessage());
    }

    @Test
    void testExecutePost_Success() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");

        String jsonResponse = "{\"key\":\"responseValue\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(201);
        when(httpResponseMock.body()).thenReturn(jsonResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(EMPTY_HEADERS);

        // When
        TestResponse actualResponse = resourceAuthorizationClient.executePost(
                url, requestPayload, ContentType.APPLICATION_JSON.toString(), TestResponse.class);

        // Then
        assertNotNull(actualResponse);
        assertEquals("responseValue", actualResponse.getKey());
    }

    @Test
    void testExecutePost_WithExtraHeaders_SendsThem() throws Exception {
        // Given
        String url = "https://example.com/token";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn("{\"key\":\"v\"}".getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(EMPTY_HEADERS);

        org.mockito.ArgumentCaptor<HttpRequest> requestCaptor = org.mockito.ArgumentCaptor.forClass(HttpRequest.class);

        // When
        TestResponse actualResponse = resourceAuthorizationClient.executePost(
                url, "grant_type=refresh_token",
                "application/x-www-form-urlencoded",
                Map.of("Authorization", "Basic Zm9vOmJhcg=="),
                TestResponse.class);

        // Then
        assertNotNull(actualResponse);
        org.mockito.Mockito.verify(httpClientMock).send(requestCaptor.capture(), any(HttpResponse.BodyHandler.class));
        HttpRequest captured = requestCaptor.getValue();
        assertEquals("Basic Zm9vOmJhcg==", captured.headers().firstValue("Authorization").orElse(null));
        assertEquals("application/x-www-form-urlencoded", captured.headers().firstValue("Content-Type").orElse(null));
    }

    @Test
    void testExecutePost_NotFoundStatusStatus() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");

        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(404);

        HttpHeaders httpHeadersMock = mock(HttpHeaders.class);
        when(httpHeadersMock.map()).thenReturn(Map.of("header_1", List.of("header_1_value")));
        when(httpResponseMock.headers()).thenReturn(httpHeadersMock);

        // When
        HttpException exception = assertThrows(HttpException.class, () ->
                resourceAuthorizationClient.executePost(url, requestPayload,
                        ContentType.APPLICATION_JSON.toString(), TestResponse.class));

        // Then
        assertEquals(404, exception.getStatus().getCode());
        assertEquals("Authorization server returns error code", exception.getMessage());
        assertTrue(exception.getHeaders().isEmpty());
    }

    @Test
    void testExecutePost_UnauthorizedStatus() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");

        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(401);

        HttpHeaders httpHeadersMock = mock(HttpHeaders.class);
        when(httpHeadersMock.map()).thenReturn(Map.of("header_1", List.of("header_1_value")));
        when(httpResponseMock.headers()).thenReturn(httpHeadersMock);
        Map<String, String> expectedResponseHeaders = Map.of("header_1", "header_1_value");
        when(httpHeadersHandler.convertHttpHeadersToMap(httpHeadersMock)).thenReturn(expectedResponseHeaders);

        // When
        HttpException exception = assertThrows(HttpException.class, () ->
                resourceAuthorizationClient.executePost(url, requestPayload,
                        ContentType.APPLICATION_JSON.toString(), TestResponse.class));

        // Then
        assertEquals(401, exception.getStatus().getCode());
        assertEquals("Authorization server returns 401 error code", exception.getMessage());
        assertEquals(expectedResponseHeaders, exception.getHeaders());
    }

    @Test
    void testExecutePost_UnresolvedAddressException() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");

        UnresolvedAddressException unresolved = new UnresolvedAddressException();
        ConnectException innerConnect = new ConnectException("Inner connection error.");
        innerConnect.initCause(unresolved);
        ConnectException outerConnect = new ConnectException("Outer connection error.");
        outerConnect.initCause(innerConnect);

        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(outerConnect);

        // When && Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
                resourceAuthorizationClient.executePost(
                        url, requestPayload,
                        ContentType.APPLICATION_JSON.toString(),
                        TestResponse.class
                )
        );
        assertEquals("Connection failed: The specified endpoint '%s' is invalid or unreachable.".formatted(url), exception.getMessage());
    }

    @Test
    void testExecutePost_ConnectException() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");

        ConnectException innerConnect = new ConnectException("Inner connection error.");
        ConnectException outerConnect = new ConnectException("Outer connection error.");
        outerConnect.initCause(innerConnect);

        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenThrow(outerConnect);

        // When && Then
        ConnectException exception = assertThrows(ConnectException.class, () ->
                resourceAuthorizationClient.executePost(
                        url, requestPayload,
                        ContentType.APPLICATION_JSON.toString(),
                        TestResponse.class
                )
        );
        assertEquals("Cannot connect to https://example.com/resource", exception.getMessage());
    }

    @Test
    void testExecuteGet_OauthErrorInSuccessResponse() throws Exception {
        // Given
        String url = "https://example.com/token";
        String errorResponse = "{\"error\":\"invalid_grant\",\"error_description\":\"The authorization code has expired\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(errorResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(EMPTY_HEADERS);

        // When
        HttpException exception = assertThrows(HttpException.class,
                () -> resourceAuthorizationClient.executeGet(url, TestResponse.class));

        // Then
        assertEquals(400, exception.getStatus().getCode());
        assertTrue(exception.getMessage().contains("invalid_grant"));
        assertTrue(exception.getMessage().contains("The authorization code has expired"));
    }

    @Test
    void testExecutePost_OauthErrorInSuccessResponse() throws Exception {
        // Given
        String url = "https://example.com/token";
        TestRequest requestPayload = new TestRequest("testValue");
        String errorResponse = "{\"error\":\"invalid_client\",\"error_description\":\"Invalid redirect_uri\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(errorResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(EMPTY_HEADERS);

        // When
        HttpException exception = assertThrows(HttpException.class,
                () -> resourceAuthorizationClient.executePost(url, requestPayload,
                        ContentType.APPLICATION_JSON.toString(), TestResponse.class));

        // Then
        assertEquals(400, exception.getStatus().getCode());
        assertTrue(exception.getMessage().contains("invalid_client"));
        assertTrue(exception.getMessage().contains("Invalid redirect_uri"));
    }

    @Test
    void testExecutePost_OauthErrorWithoutDescription() throws Exception {
        // Given
        String url = "https://example.com/token";
        TestRequest requestPayload = new TestRequest("testValue");
        String errorResponse = "{\"error\":\"server_error\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(errorResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(EMPTY_HEADERS);

        // When
        HttpException exception = assertThrows(HttpException.class,
                () -> resourceAuthorizationClient.executePost(url, requestPayload,
                        ContentType.APPLICATION_JSON.toString(), TestResponse.class));

        // Then
        assertEquals(400, exception.getStatus().getCode());
        assertTrue(exception.getMessage().contains("server_error"));
        assertTrue(exception.getMessage().contains("no description"));
    }

    @Test
    void testExecuteGet_ValidResponseNotTreatedAsOauthError() throws Exception {
        String url = "https://example.com/resource";
        String jsonResponse = "{\"key\":\"value\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(jsonResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(EMPTY_HEADERS);

        // When
        TestResponse actualResponse = resourceAuthorizationClient.executeGet(url, TestResponse.class);

        // Then
        assertNotNull(actualResponse);
        assertEquals("value", actualResponse.getKey());
    }

    @Test
    void testExecuteGet_DecodesXgzipAsGzip() throws Exception {
        String url = "https://example.com/resource";
        String jsonResponse = "{\"key\":\"value\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(gzip(jsonResponse));
        when(httpResponseMock.headers()).thenReturn(
                HttpHeaders.of(Map.of("Content-Encoding", List.of("x-gzip")), (k, v) -> true));

        TestResponse actualResponse = resourceAuthorizationClient.executeGet(url, TestResponse.class);

        assertNotNull(actualResponse);
        assertEquals("value", actualResponse.getKey());
    }

    @Test
    void testExecuteGet_FailsOnStackedCodings() throws Exception {
        String url = "https://example.com/resource";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn("ignored".getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(
                HttpHeaders.of(Map.of("Content-Encoding", List.of("gzip, deflate")), (k, v) -> true));

        HttpException exception = assertThrows(HttpException.class,
                () -> resourceAuthorizationClient.executeGet(url, TestResponse.class));

        assertTrue(exception.getMessage().contains("gzip"));
        assertTrue(exception.getMessage().contains("deflate"));
    }

    @Test
    void testExecuteGet_FailsWhenContentEncodingIsUnsupported() throws Exception {
        String url = "https://example.com/resource";
        String jsonResponse = "{\"key\":\"value\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(jsonResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(
                HttpHeaders.of(Map.of("Content-Encoding", List.of("br")), (k, v) -> true));

        HttpException exception = assertThrows(HttpException.class,
                () -> resourceAuthorizationClient.executeGet(url, TestResponse.class));

        assertTrue(exception.getMessage().contains("br"));
    }

    @Test
    void testExecuteGet_TreatsBodyAsRawWhenContentEncodingIsIdentity() throws Exception {
        String url = "https://example.com/resource";
        String jsonResponse = "{\"key\":\"value\"}";
        HttpResponse<byte[]> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(jsonResponse.getBytes(StandardCharsets.UTF_8));
        when(httpResponseMock.headers()).thenReturn(
                HttpHeaders.of(Map.of("Content-Encoding", List.of("identity")), (k, v) -> true));

        TestResponse actualResponse = resourceAuthorizationClient.executeGet(url, TestResponse.class);

        assertNotNull(actualResponse);
        assertEquals("value", actualResponse.getKey());
    }

    private static byte[] gzip(String value) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(baos)) {
            gz.write(value.getBytes(StandardCharsets.UTF_8));
        }
        return baos.toByteArray();
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class TestRequest {
        private String key;
    }

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class TestResponse {
        private String key;
    }

    /**
     * A server may answer the discovery probe with an SSE stream it holds open indefinitely.
     * {@link java.net.http.HttpRequest#timeout} stops once the response headers arrive and does not
     * bound the body, so reading that body would block the calling thread - and with it the toolset
     * create/update request - for as long as the peer keeps the stream open. The probe must come
     * back with the status and headers and leave the body alone.
     */
    @Test
    void probeReturnsWithoutReadingAnEndlessResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch released = new CountDownLatch(1);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try {
                exchange.getResponseBody().write(": open\n\n".getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
                released.await(30, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                // the client abandons the body; nothing to do
            }
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            ResourceAuthorizationClient client = new ResourceAuthorizationClient((java.net.ProxySelector) null);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

            assertTimeoutPreemptively(Duration.ofSeconds(10), () ->
                    client.executeProbe(url, Map.of("jsonrpc", "2.0"), ContentType.APPLICATION_JSON.toString(),
                            Map.of("Accept", "application/json, text/event-stream")));
        } finally {
            released.countDown();
            server.stop(0);
        }
    }

    /** The challenge a probe exists to collect must survive the body being abandoned. */
    @Test
    void probeSurfacesTheChallengeHeadersOnUnauthorized() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            exchange.getResponseHeaders().add("WWW-Authenticate",
                    "Bearer resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"");
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            ResourceAuthorizationClient client = new ResourceAuthorizationClient((java.net.ProxySelector) null);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

            HttpException e = assertThrows(HttpException.class, () ->
                    client.executeProbe(url, Map.of("jsonrpc", "2.0"), ContentType.APPLICATION_JSON.toString(), Map.of()));

            assertEquals("https://example.com/.well-known/oauth-protected-resource/mcp",
                    new HttpHeadersHandler().extractMetadataUrl(e.getHeaders()).orElse(null));
        } finally {
            server.stop(0);
        }
    }

    /** A probe that opens a session on a stateful server must close it rather than orphan it. */
    @Test
    void probeClosesTheSessionItOpens() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch deleted = new CountDownLatch(1);
        List<String> deletedSessions = Collections.synchronizedList(new java.util.ArrayList<>());
        server.createContext("/mcp", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                deletedSessions.add(exchange.getRequestHeaders().getFirst("Mcp-Session-Id"));
                exchange.sendResponseHeaders(204, -1);
                exchange.close();
                deleted.countDown();
                return;
            }
            exchange.getResponseHeaders().add("Mcp-Session-Id", "session-42");
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        try {
            ResourceAuthorizationClient client = new ResourceAuthorizationClient((java.net.ProxySelector) null);
            String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

            client.executeProbe(url, Map.of("jsonrpc", "2.0"), ContentType.APPLICATION_JSON.toString(), Map.of());

            assertTrue(deleted.await(10, TimeUnit.SECONDS), "Expected the probe to close its session");
            assertEquals(List.of("session-42"), deletedSessions);
        } finally {
            server.stop(0);
        }
    }
}
