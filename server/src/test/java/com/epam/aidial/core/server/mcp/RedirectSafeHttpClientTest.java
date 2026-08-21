package com.epam.aidial.core.server.mcp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises {@link RedirectSafeHttpClient} entirely against a mocked delegate {@code HttpClient} - no
 * real sockets, so this never depends on loopback networking being reliable in a CI sandbox.
 */
class RedirectSafeHttpClientTest {

    @Test
    void followsSameOriginRedirect_307_andForwardsHeaders() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "http://localhost/redirected"));
        HttpResponse<String> finalResponse = mockResponse(200, noHeaders());
        when(finalResponse.body()).thenReturn("ok");
        stubResponses(delegate, redirectResponse, finalResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp"))
                .header("API-KEY", "secret")
                .GET()
                .build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(200, response.statusCode());
        assertEquals("ok", response.body());

        HttpRequest redirected = capturedRequests(delegate, 2).get(1);
        assertEquals(URI.create("http://localhost/redirected"), redirected.uri());
        assertEquals(List.of("secret"), redirected.headers().allValues("API-KEY"));
    }

    @Test
    void followsSameOriginRedirect_308_andPreservesMethodAndBody() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(308, headersOf("Location", "http://localhost/redirected"));
        HttpResponse<String> finalResponse = mockResponse(200, noHeaders());
        stubResponses(delegate, redirectResponse, finalResponse);

        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.ofString("{\"payload\":\"foo\"}");
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).POST(body).build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(200, response.statusCode());

        HttpRequest redirected = capturedRequests(delegate, 2).get(1);
        assertEquals("POST", redirected.method());
        // rebuild() reuses the same BodyPublisher instance rather than re-encoding the body
        assertSame(body, redirected.bodyPublisher().orElse(null));
    }

    @Test
    void refusesCrossOriginRedirect_andNeverSendsHeaderToIt() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "http://127.0.0.1:19881/final"));
        stubResponses(delegate, redirectResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19880/mcp"))
                .header("API-KEY", "secret")
                .GET()
                .build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(307, response.statusCode());
        assertEquals("http://127.0.0.1:19881/final", response.headers().firstValue("Location").orElse(null));
        // the crux of the guarantee: no second request is ever attempted, so the API-KEY header
        // sent above never has anywhere else to leak to
        verify(delegate, times(1)).sendAsync(any(), any());
    }

    @Test
    void capsRedirectLoopAtMaxRedirects() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "?redirected=1"));
        stubResponses(delegate, redirectResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(307, response.statusCode());
        verify(delegate, times(1 + McpClientUtils.MAX_MCP_REDIRECTS)).sendAsync(any(), any());
    }

    @Test
    void ignoresMalformedLocation_andReturnsPromptly() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "http://[malformed"));
        stubResponses(delegate, redirectResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(307, response.statusCode());
        verify(delegate, times(1)).sendAsync(any(), any());
    }

    @Test
    void missingLocationHeaderReturnsRedirectAsIs() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, noHeaders());
        stubResponses(delegate, redirectResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(307, response.statusCode());
        verify(delegate, times(1)).sendAsync(any(), any());
    }

    @Test
    void doesNotFollow302Redirect() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(302, headersOf("Location", "/redirected"));
        stubResponses(delegate, redirectResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(302, response.statusCode());
        verify(delegate, times(1)).sendAsync(any(), any());
    }

    @Test
    void nonRedirectResponsePassesThroughUnchanged() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> okResponse = mockResponse(200, headersOf("X-Test", "value"));
        when(okResponse.body()).thenReturn("hello");
        stubResponses(delegate, okResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(200, response.statusCode());
        assertEquals("hello", response.body());
        assertEquals("value", response.headers().firstValue("X-Test").orElse(null));
        verify(delegate, times(1)).sendAsync(any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void discardsBodyOnlyForRedirectsItWillActuallyFollow() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> finalResponse = mockResponse(200, noHeaders());
        ArgumentCaptor<HttpResponse.BodyHandler<String>> handlerCaptor = ArgumentCaptor.forClass(HttpResponse.BodyHandler.class);
        when(delegate.<String>sendAsync(any(), handlerCaptor.capture()))
                .thenReturn(CompletableFuture.completedFuture(finalResponse));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();
        HttpResponse.BodySubscriber<String> callerSubscriber = mock(HttpResponse.BodySubscriber.class);
        HttpResponse.BodyHandler<String> callerHandler = responseInfo -> callerSubscriber;

        client.sendAsync(request, callerHandler).join();
        HttpResponse.BodyHandler<String> guardedHandler = handlerCaptor.getValue();

        assertNotSame(callerSubscriber,
                guardedHandler.apply(mockResponseInfo(307, headersOf("Location", "/next"))),
                "a same-origin 307 will be followed, so its body must never reach the caller's real handler");
        assertSame(callerSubscriber,
                guardedHandler.apply(mockResponseInfo(307, headersOf("Location", "http://evil.example/next"))),
                "a cross-origin 307 will NOT be followed, so the caller must still get a real subscriber");
        assertSame(callerSubscriber, guardedHandler.apply(mockResponseInfo(200, noHeaders())));
    }

    @Test
    void followRedirectsReportsNormal_otherGettersDelegate() {
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.version()).thenReturn(HttpClient.Version.HTTP_1_1);
        when(delegate.connectTimeout()).thenReturn(Optional.of(Duration.ofSeconds(5)));
        HttpClient client = new RedirectSafeHttpClient(delegate);

        assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
        assertEquals(HttpClient.Version.HTTP_1_1, client.version());
        assertEquals(Optional.of(Duration.ofSeconds(5)), client.connectTimeout());
    }

    @Test
    void lifecycleMethodsDelegateToTheRealClient() throws InterruptedException {
        HttpClient delegate = mock(HttpClient.class);
        when(delegate.isTerminated()).thenReturn(true);
        when(delegate.awaitTermination(any())).thenReturn(true);
        HttpClient client = new RedirectSafeHttpClient(delegate);

        client.close();
        client.shutdown();
        client.shutdownNow();

        assertTrue(client.isTerminated());
        assertTrue(client.awaitTermination(Duration.ofSeconds(1)));
        verify(delegate).close();
        verify(delegate).shutdown();
        verify(delegate).shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private static HttpResponse<String> mockResponse(int statusCode, HttpHeaders headers) {
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(statusCode);
        when(response.headers()).thenReturn(headers);
        return response;
    }

    private static HttpResponse.ResponseInfo mockResponseInfo(int statusCode, HttpHeaders headers) {
        HttpResponse.ResponseInfo responseInfo = mock(HttpResponse.ResponseInfo.class);
        when(responseInfo.statusCode()).thenReturn(statusCode);
        when(responseInfo.headers()).thenReturn(headers);
        return responseInfo;
    }

    private static HttpHeaders headersOf(String name, String value) {
        return HttpHeaders.of(Map.of(name, List.of(value)), (a, b) -> true);
    }

    private static HttpHeaders noHeaders() {
        return HttpHeaders.of(Map.of(), (a, b) -> true);
    }

    /**
     * Stubs {@code delegate.sendAsync} to return each response in order, repeating the last one for
     * any further calls - so a single response means "always respond with this" (e.g. an endless redirect).
     */
    @SafeVarargs
    private static void stubResponses(HttpClient delegate, HttpResponse<String>... responses) {
        AtomicInteger callIndex = new AtomicInteger();
        when(delegate.<String>sendAsync(any(), any())).thenAnswer(invocation -> {
            int index = Math.min(callIndex.getAndIncrement(), responses.length - 1);
            return CompletableFuture.completedFuture(responses[index]);
        });
    }

    private static List<HttpRequest> capturedRequests(HttpClient delegate, int expectedCount) {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate, times(expectedCount)).sendAsync(captor.capture(), any());
        return captor.getAllValues();
    }
}
