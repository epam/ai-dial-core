package com.epam.aidial.core.server.mcp;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    /**
     * A followed redirect's body is never read, so a server that announces one and never sends it must not stall
     * the request: the redirect is followed as soon as its headers are in.
     */
    @Test
    void followsRedirectWithoutWaitingForItsBody() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "http://localhost/redirected"));
        HttpResponse<String> finalResponse = mockResponse(200, noHeaders());
        AtomicInteger calls = new AtomicInteger();
        when(delegate.<String>sendAsync(any(), any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() > 0) {
                return CompletableFuture.completedFuture(finalResponse);
            }
            HttpResponse.BodySubscriber<String> subscriber = invocation.<HttpResponse.BodyHandler<String>>getArgument(1)
                    .apply(mockResponseInfo(307, headersOf("Location", "http://localhost/redirected")));
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // the announced body never arrives
                }

                @Override
                public void cancel() {
                    // nothing to release
                }
            });
            return subscriber.getBody().thenApply(body -> redirectResponse);
        });

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        HttpResponse<String> response = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join());

        assertEquals(200, response.statusCode());
    }

    /**
     * Abandoning the body cancels the exchange, and a client may report that as a failure even though the redirect
     * was received - that failure is self-inflicted, so the redirect is still followed.
     */
    @Test
    void followsRedirectWhenAbandoningItsBodyFailsTheExchange() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> finalResponse = mockResponse(200, noHeaders());
        AtomicInteger calls = new AtomicInteger();
        when(delegate.<String>sendAsync(any(), any())).thenAnswer(invocation -> {
            if (calls.getAndIncrement() > 0) {
                return CompletableFuture.completedFuture(finalResponse);
            }
            HttpResponse.BodySubscriber<String> subscriber = invocation.<HttpResponse.BodyHandler<String>>getArgument(1)
                    .apply(mockResponseInfo(307, headersOf("Location", "http://localhost/redirected")));
            IOException reset = new IOException("Stream 1 cancelled");
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override
                public void request(long n) {
                    // never delivers
                }

                @Override
                public void cancel() {
                    subscriber.onError(reset);
                }
            });
            return CompletableFuture.failedFuture(reset);
        });

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        assertEquals(200, client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join().statusCode());
    }

    /** A failure before any redirect arrived is a real failure and must reach the caller unchanged. */
    @Test
    void propagatesFailureWhenNoRedirectArrived() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        IOException reset = new IOException("Connection reset");
        when(delegate.<String>sendAsync(any(), any())).thenReturn(CompletableFuture.failedFuture(reset));

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        CompletionException e = assertThrows(CompletionException.class,
                () -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join());
        assertSame(reset, e.getCause());
    }

    /**
     * A request's timeout bounds the request as a whole: a redirect gets only what is left of it, rather
     * than restarting the clock on every hop.
     */
    @Test
    void redirectGetsOnlyWhatIsLeftOfTheTimeout() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "http://localhost/redirected"));
        HttpResponse<String> finalResponse = mockResponse(200, noHeaders());
        stubSlowFirstResponse(delegate, Duration.ofMillis(300), redirectResponse, finalResponse);

        Duration timeout = Duration.ofSeconds(10);
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).timeout(timeout).GET().build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        Duration redirectTimeout = capturedRequests(delegate, 2).get(1).timeout().orElseThrow();
        assertTrue(redirectTimeout.compareTo(timeout.minusMillis(300)) <= 0,
                "the redirect restarted the timeout: " + redirectTimeout);
    }

    @Test
    void failsOnceTheTimeoutIsSpentOnRedirects() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "http://localhost/redirected"));
        stubSlowFirstResponse(delegate, Duration.ofMillis(300), redirectResponse, redirectResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp"))
                .timeout(Duration.ofMillis(200)).GET().build();

        CompletionException e = assertThrows(CompletionException.class,
                () -> client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join());

        assertInstanceOf(HttpTimeoutException.class, e.getCause());
        capturedRequests(delegate, 1);
    }

    @Test
    void redirectOfRequestWithoutTimeoutStaysWithoutTimeout() {
        HttpClient delegate = mock(HttpClient.class);
        HttpClient client = new RedirectSafeHttpClient(delegate);
        HttpResponse<String> redirectResponse = mockResponse(307, headersOf("Location", "http://localhost/redirected"));
        HttpResponse<String> finalResponse = mockResponse(200, noHeaders());
        stubResponses(delegate, redirectResponse, finalResponse);

        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost/mcp")).GET().build();

        client.sendAsync(request, HttpResponse.BodyHandlers.ofString()).join();

        assertEquals(Optional.empty(), capturedRequests(delegate, 2).get(1).timeout());
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

    /** Like {@link #stubResponses}, but the first response only arrives after {@code delay}. */
    private static void stubSlowFirstResponse(HttpClient delegate, Duration delay,
                                              HttpResponse<String> first, HttpResponse<String> rest) {
        AtomicInteger callIndex = new AtomicInteger();
        when(delegate.<String>sendAsync(any(), any())).thenAnswer(invocation -> {
            if (callIndex.getAndIncrement() > 0) {
                return CompletableFuture.completedFuture(rest);
            }
            Thread.sleep(delay.toMillis());
            return CompletableFuture.completedFuture(first);
        });
    }

    private static List<HttpRequest> capturedRequests(HttpClient delegate, int expectedCount) {
        ArgumentCaptor<HttpRequest> captor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(delegate, times(expectedCount)).sendAsync(captor.capture(), any());
        return captor.getAllValues();
    }
}
