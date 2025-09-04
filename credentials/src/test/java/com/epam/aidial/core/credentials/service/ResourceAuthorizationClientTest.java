package com.epam.aidial.core.credentials.service;

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

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void testExecuteGet_NotFoundStatus() throws Exception {
        // Given
        String url = "https://example.com/resource";
        HttpResponse<String> httpResponseMock = mock(HttpResponse.class);
        when(httpClientMock.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(httpResponseMock);
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

        // When
        HttpException exception = assertThrows(HttpException.class, () -> resourceAuthorizationClient.executeGet(url, TestResponse.class));

        //Then
        assertEquals(401, exception.getStatus().getCode());
        assertEquals("Authorization server returns error code", exception.getMessage());
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
