package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.service.metadata.HttpHeadersHandler;
import com.epam.aidial.core.storage.http.HttpException;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.apache.hc.core5.http.ContentType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.ConnectException;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.UnresolvedAddressException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResourceAuthorizationClientTest {

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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        TestResponse expectedResponse = new TestResponse("value");
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(jsonResponse);

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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(201);
        when(httpResponseMock.body()).thenReturn(jsonResponse);

        // When
        TestResponse actualResponse = resourceAuthorizationClient.executePost(
                url, requestPayload, ContentType.APPLICATION_JSON.toString(), TestResponse.class);

        // Then
        assertNotNull(actualResponse);
        assertEquals("responseValue", actualResponse.getKey());
    }

    @Test
    void testExecutePost_NotFoundStatusStatus() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");

        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
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

        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(errorResponse);

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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(errorResponse);

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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(errorResponse);

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
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(200);
        when(httpResponseMock.body()).thenReturn(jsonResponse);

        // When
        TestResponse actualResponse = resourceAuthorizationClient.executeGet(url, TestResponse.class);

        // Then
        assertNotNull(actualResponse);
        assertEquals("value", actualResponse.getKey());
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
}
