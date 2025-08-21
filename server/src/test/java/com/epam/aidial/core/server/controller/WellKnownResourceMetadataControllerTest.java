package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.data.wellknown.ResourceMetadata;
import com.epam.aidial.core.server.service.WellKnownResourceMetadataService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WellKnownResourceMetadataControllerTest {

    @Mock
    private WellKnownResourceMetadataService service;
    @Mock
    private HttpServerRequest request;
    @Mock
    private HttpServerResponse response;

    @InjectMocks
    private WellKnownResourceMetadataController controller;

    @BeforeEach
    void setup() {
        when(request.response()).thenReturn(response);
        when(response.setStatusCode(anyInt())).thenReturn(response);
    }

    @Test
    void handle_noResourceMetadata_returns404() {
        when(service.resolveResourceMetadata(any())).thenReturn(Optional.empty());

        controller.handle(request);

        verify(response).setStatusCode(eq(404));
        verify(response).end();
    }

    @Test
    void handle_withResourceMetadata_returns200() throws JsonProcessingException {
        when(response.putHeader(any(CharSequence.class), anyString())).thenReturn(response);
        ResourceMetadata resourceMetadata = new ResourceMetadata();
        resourceMetadata.setResource("https://example.com/resource");
        resourceMetadata.setAuthorizationServers(List.of("https://auth.example.com"));
        when(service.resolveResourceMetadata(any())).thenReturn(Optional.of(resourceMetadata));

        controller.handle(request);

        // Should be 200 OK
        verify(response).setStatusCode(eq(200));
        verify(response).putHeader(eq(HttpHeaderNames.CONTENT_TYPE), eq("application/json"));

        // Verify JSON contains resource and authorization_servers fields
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(response).end(captor.capture());
        String body = captor.getValue();
        JsonNode json = ProxyUtil.MAPPER.readTree(body);
        assertEquals("https://example.com/resource", json.get("resource").asText());
        assertEquals(1, json.get("authorization_servers").size());
        assertEquals("https://auth.example.com", json.get("authorization_servers").get(0).asText());
    }

    @Test
    void handle_exceptionDuringProcessing_returns500() {
        when(service.resolveResourceMetadata(any())).thenThrow(new RuntimeException("fail"));

        controller.handle(request);

        verify(response).setStatusCode(eq(500));
        verify(response).end();
    }

}