package com.epam.aidial.core.credentials.service.metadata;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.validation.ProtectedResourceMetadataValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the full protected-resource-metadata discovery flow (fetch -> parse -> validate)
 * against a real {@link ResourceAuthorizationClient} backed by a mocked transport, so the
 * discovered JSON goes through actual deserialization.
 */
class ProtectedResourceMetadataServiceTest {

    private static final String RESOURCE_ID = "toolsets/foo/bar";
    private static final String RESOURCE_ENDPOINT = "https://gitlab.com/mcp";
    private static final HttpHeaders EMPTY_HEADERS = HttpHeaders.of(Map.of(), (k, v) -> true);

    @Mock
    private HttpClient httpClient;

    @InjectMocks
    private ResourceAuthorizationClient client;

    private ProtectedResourceMetadataService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProtectedResourceMetadataService(
                client, new ProtectedResourceMetadataValidator(), new HttpHeadersHandler());
    }

    /**
     * GitLab-style metadata where {@code resource} is a single-element array. Discovery must
     * tolerate it, unwrap it to the scalar URL, and pass validation.
     */
    @Test
    void discoversMetadataWhenResourceIsSingleElementArray() throws Exception {
        stubDiscovery("""
                {"resource": ["https://gitlab.com"], "authorization_servers": ["https://gitlab.com"]}""");

        AuthorizationServerProtectedResourceMetadata metadata =
                service.getProtectedResourceMetadata(RESOURCE_ID, RESOURCE_ENDPOINT);

        assertEquals("https://gitlab.com", metadata.getResource());
        assertEquals(List.of("https://gitlab.com"), metadata.getAuthorizationServers());
    }

    @Test
    void discoversMetadataWhenResourceIsScalarString() throws Exception {
        stubDiscovery("""
                {"resource": "https://gitlab.com", "authorization_servers": ["https://gitlab.com"]}""");

        AuthorizationServerProtectedResourceMetadata metadata =
                service.getProtectedResourceMetadata(RESOURCE_ID, RESOURCE_ENDPOINT);

        assertEquals("https://gitlab.com", metadata.getResource());
    }

    @Test
    void abortsDiscoveryWhenResourceIsMultiElementArray() throws Exception {
        stubDiscovery("""
                {"resource": ["https://gitlab.com", "https://evil.com"], "authorization_servers": ["https://gitlab.com"]}""");

        assertThrows(IllegalArgumentException.class,
                () -> service.getProtectedResourceMetadata(RESOURCE_ID, RESOURCE_ENDPOINT));
    }

    /**
     * The initial POST probe returns 200 (no WWW-Authenticate hint), so discovery falls back to the
     * well-known GET, which returns the given metadata body.
     */
    private void stubDiscovery(String metadataJson) throws Exception {
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    return "GET".equals(request.method())
                            ? response(metadataJson)
                            : response("{}");
                });
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> response(String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(EMPTY_HEADERS);
        return response;
    }
}