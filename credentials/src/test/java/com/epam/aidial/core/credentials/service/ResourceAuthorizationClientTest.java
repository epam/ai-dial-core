package com.epam.aidial.core.credentials.service;

import com.epam.aidial.core.credentials.exception.CredentialsInternalException;
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

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ResourceAuthorizationClientTest {

    @Mock
    private HttpClient httpClientMock;

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
    void testExecuteGet_NonSuccessStatus() throws Exception {
        // Given
        String url = "https://example.com/resource";
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
        when(httpResponseMock.statusCode()).thenReturn(404);
        when(httpResponseMock.body()).thenReturn("Resource not found");

        // When
        HttpException exception = assertThrows(HttpException.class, () -> resourceAuthorizationClient.executeGet(url, TestResponse.class));

        //Then
        assertEquals(404, exception.getStatus().getCode());
        assertEquals("Resource not found", exception.getMessage());
    }

    @Test
    void testExecuteGet_IOException() throws Exception {
        // Given
        String url = "https://example.com/resource";
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("IO exception"));

        // When & Then
        assertThrows(CredentialsInternalException.class, () -> {
            resourceAuthorizationClient.executeGet(url, TestResponse.class);
        });
    }

    @Test
    void testExecuteGet_InterruptedException() throws Exception {
        // Given
        String url = "https://example.com/resource";
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new InterruptedException("Interrupted Exception"));

        // When & Then
        assertThrows(CredentialsInternalException.class, () -> {
            resourceAuthorizationClient.executeGet(url, TestResponse.class);
        });
        assertTrue(Thread.currentThread().isInterrupted());
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
    void testExecutePost_IOError() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new IOException("IO exception"));

        // When & Then
        assertThrows(CredentialsInternalException.class, () -> resourceAuthorizationClient.executePost(
            url, requestPayload, ContentType.APPLICATION_JSON.toString(), TestResponse.class));
    }

    @Test
    void testExecutePost_UnhandledException() throws Exception {
        // Given
        String url = "https://example.com/resource";
        TestRequest requestPayload = new TestRequest("testValue");
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
            .thenThrow(new RuntimeException("Runtime Exception"));

        // When & Then
        assertThrows(CredentialsInternalException.class, () -> resourceAuthorizationClient.executePost(
            url, requestPayload, ContentType.APPLICATION_JSON.toString(), TestResponse.class));
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
