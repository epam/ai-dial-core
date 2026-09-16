package com.epam.aidial.core.credentials.service.metadata;

import com.epam.aidial.core.credentials.data.registration.AuthorizationServerProtectedResourceMetadata;
import com.epam.aidial.core.credentials.service.ResourceAuthorizationClient;
import com.epam.aidial.core.credentials.validation.ProtectedResourceMetadataValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;

import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    /** Real implementation: the header parsing under test must not be stubbed away. */
    @Spy
    private HttpHeadersHandler httpHeadersHandler = new HttpHeadersHandler();

    @InjectMocks
    private ResourceAuthorizationClient client;

    private ProtectedResourceMetadataService service;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new ProtectedResourceMetadataService(
                client, new ProtectedResourceMetadataValidator(), httpHeadersHandler);
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

    /**
     * Reproduces a multi-product host (DealCloud-style): every MCP endpoint on the domain has its
     * own authorization server, the per-endpoint pointer is published only in the 401's
     * WWW-Authenticate header, and the domain root serves a different, tenant-wide document.
     *
     * <p>RFC 9728 lets a server implement either discovery mechanism, and the MCP spec requires the
     * client to prefer the pointer when one is present. Ignoring it here does not merely lose the
     * hint - it silently resolves the wrong authorization server.
     */
    @Test
    void prefersResourceMetadataPointerOverWellKnownFallback() throws Exception {
        String endpoint = "https://dealcloud.example/mcp/productA";
        String pointer = "https://dealcloud.example/.well-known/oauth-protected-resource/products/a";
        String productMetadata = """
                {"resource": "https://dealcloud.example/mcp/productA",
                 "authorization_servers": ["https://dealcloud.example/auth/productA"]}""";
        String tenantWideMetadata = """
                {"resource": "https://dealcloud.example",
                 "authorization_servers": ["https://dealcloud.example/auth/tenant"]}""";

        List<String> requested = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    String url = request.uri().toString();
                    requested.add(request.method() + " " + url);
                    if ("POST".equals(request.method())) {
                        return unauthorized(pointer);
                    }
                    if (pointer.equals(url)) {
                        return response(productMetadata);
                    }
                    if ("https://dealcloud.example/.well-known/oauth-protected-resource".equals(url)) {
                        return response(tenantWideMetadata);
                    }
                    return notFound();
                });

        AuthorizationServerProtectedResourceMetadata metadata =
                service.getProtectedResourceMetadata(RESOURCE_ID, endpoint);

        assertTrue(requested.contains("GET " + pointer),
                "Discovery never followed the resource_metadata pointer. Requests made: " + requested);
        assertEquals(List.of("https://dealcloud.example/auth/productA"), metadata.getAuthorizationServers(),
                "Discovery resolved the wrong authorization server for this endpoint");
    }

    /**
     * A 401 carrying the pointer, with the header name lowercased exactly as the JDK's HttpClient
     * delivers it off the wire.
     */
    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> unauthorized(String resourceMetadataUrl) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401);
        when(response.body()).thenReturn("".getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(HttpHeaders.of(
                Map.of("www-authenticate",
                        List.of("Bearer realm=\"OAuth\", resource_metadata=\"" + resourceMetadataUrl + "\"")),
                (k, v) -> true));
        return response;
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> notFound() {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(404);
        when(response.body()).thenReturn("".getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(EMPTY_HEADERS);
        return response;
    }

    /**
     * Same multi-product host, fronted by a server strict enough to reject anything that is not a
     * well-formed Streamable-HTTP MCP request. Only a proper {@code initialize} that accepts
     * {@code text/event-stream} draws the 401 challenge carrying the per-endpoint pointer; anything
     * else gets a protocol error, and discovery then settles on the tenant-wide document at the
     * domain root - the wrong authorization server, returned as a success with no error surfaced.
     */
    @Test
    void probesWithValidMcpInitializeSoStrictServersIssueTheChallenge() throws Exception {
        String endpoint = "https://dealcloud.example/mcp/productA";
        String pointer = "https://dealcloud.example/.well-known/oauth-protected-resource/products/a";
        String productMetadata = """
                {"resource": "https://dealcloud.example/mcp/productA",
                 "authorization_servers": ["https://dealcloud.example/auth/productA"]}""";
        String tenantWideMetadata = """
                {"resource": "https://dealcloud.example",
                 "authorization_servers": ["https://dealcloud.example/auth/tenant"]}""";

        List<String> rejected = new ArrayList<>();
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest request = invocation.getArgument(0);
                    if ("POST".equals(request.method())) {
                        String accept = request.headers().firstValue("Accept").orElse("");
                        String body = requestBody(request);
                        if (!accept.contains("text/event-stream")) {
                            rejected.add("Accept: " + accept);
                            return status(406, "Not Acceptable: client must accept text/event-stream");
                        }
                        if (!body.contains("\"method\":\"initialize\"")) {
                            rejected.add("body: " + body);
                            return status(400, "Bad Request: expected an initialize request");
                        }
                        return unauthorized(pointer);
                    }
                    String url = request.uri().toString();
                    if (pointer.equals(url)) {
                        return response(productMetadata);
                    }
                    return "https://dealcloud.example/.well-known/oauth-protected-resource".equals(url)
                            ? response(tenantWideMetadata)
                            : notFound();
                });

        AuthorizationServerProtectedResourceMetadata metadata =
                service.getProtectedResourceMetadata(RESOURCE_ID, endpoint);

        assertTrue(rejected.isEmpty(), "Server rejected the discovery probe as malformed: " + rejected);
        assertEquals(List.of("https://dealcloud.example/auth/productA"), metadata.getAuthorizationServers(),
                "Discovery resolved the wrong authorization server for this endpoint");
    }

    /** Drains a request's body publisher so the probe payload itself can be asserted on. */
    private static String requestBody(HttpRequest request) throws Exception {
        StringBuilder body = new StringBuilder();
        CountDownLatch drained = new CountDownLatch(1);
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                body.append(StandardCharsets.UTF_8.decode(item));
            }

            @Override
            public void onError(Throwable throwable) {
                drained.countDown();
            }

            @Override
            public void onComplete() {
                drained.countDown();
            }
        });
        drained.await(5, TimeUnit.SECONDS);
        return body.toString();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<byte[]> status(int code, String body) {
        HttpResponse<byte[]> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(code);
        when(response.body()).thenReturn(body.getBytes(StandardCharsets.UTF_8));
        when(response.headers()).thenReturn(EMPTY_HEADERS);
        return response;
    }
}
