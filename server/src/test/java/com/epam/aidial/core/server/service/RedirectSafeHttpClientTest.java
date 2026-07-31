package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.TestWebServer;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedirectSafeHttpClientTest {

    @Test
    void followsSameOriginRedirect_307_andForwardsHeaders() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CopyOnWriteArrayList<String> apiKeyHeaders = new CopyOnWriteArrayList<>();
        TestWebServer.Handler handler = request -> {
            apiKeyHeaders.add(request.getHeader("API-KEY"));
            if (requestCount.getAndIncrement() == 0) {
                return new MockResponse().setResponseCode(307).setHeader("Location", "/redirected");
            }
            return new MockResponse().setResponseCode(200).setBody("ok");
        };
        try (TestWebServer server = new TestWebServer(19870, handler);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19870/mcp"))
                    .header("API-KEY", "secret")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("ok", response.body());
            assertEquals(2, requestCount.get());
            assertEquals(List.of("secret", "secret"), apiKeyHeaders);
        }
    }

    @Test
    void followsSameOriginRedirect_308_andPreservesMethodAndBody() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        CopyOnWriteArrayList<String> bodies = new CopyOnWriteArrayList<>();
        TestWebServer.Handler handler = request -> {
            bodies.add(request.getBody().readString(StandardCharsets.UTF_8));
            if (requestCount.getAndIncrement() == 0) {
                return new MockResponse().setResponseCode(308).setHeader("Location", "/redirected");
            }
            return new MockResponse().setResponseCode(200).setBody("ok");
        };
        try (TestWebServer server = new TestWebServer(19871, handler);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19871/mcp"))
                    .POST(HttpRequest.BodyPublishers.ofString("{\"payload\":\"foo\"}"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals(2, requestCount.get());
            assertEquals(List.of("{\"payload\":\"foo\"}", "{\"payload\":\"foo\"}"), bodies);
        }
    }

    @Test
    void refusesCrossOriginRedirect_andNeverSendsHeaderToIt() throws Exception {
        TestWebServer.Handler handlerA = request ->
                new MockResponse().setResponseCode(307).setHeader("Location", "http://127.0.0.1:19881/final");
        AtomicInteger crossOriginRequestCount = new AtomicInteger();
        TestWebServer.Handler handlerB = request -> {
            crossOriginRequestCount.incrementAndGet();
            return new MockResponse().setResponseCode(200).setBody("should never be reached");
        };
        try (TestWebServer serverA = new TestWebServer(19880, handlerA);
                TestWebServer serverB = new TestWebServer(19881, handlerB);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19880/mcp"))
                    .header("API-KEY", "secret")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(307, response.statusCode());
            assertEquals("http://127.0.0.1:19881/final", response.headers().firstValue("Location").orElse(null));
            assertEquals(0, crossOriginRequestCount.get());
        }
    }

    @Test
    void capsRedirectLoopAtMaxRedirects() throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        TestWebServer.Handler handler = request -> {
            requestCount.incrementAndGet();
            return new MockResponse().setResponseCode(307).setHeader("Location", "?redirected=1");
        };
        try (TestWebServer server = new TestWebServer(19882, handler);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19882/mcp")).GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(307, response.statusCode());
            assertEquals(6, requestCount.get());
        }
    }

    @Test
    void ignoresMalformedLocation_andReturnsPromptly() throws Exception {
        TestWebServer.Handler handler = request ->
                new MockResponse().setResponseCode(307).setHeader("Location", "http://[malformed");
        try (TestWebServer server = new TestWebServer(19883, handler);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19883/mcp")).GET().build();

            HttpResponse<String> response = assertTimeoutPreemptively(Duration.ofSeconds(10),
                    () -> client.send(request, HttpResponse.BodyHandlers.ofString()));

            assertEquals(307, response.statusCode());
        }
    }

    @Test
    void missingLocationHeaderReturnsRedirectAsIs() throws Exception {
        TestWebServer.Handler handler = request -> new MockResponse().setResponseCode(307);
        try (TestWebServer server = new TestWebServer(19884, handler);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19884/mcp")).GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(307, response.statusCode());
        }
    }

    @Test
    void doesNotFollow302Redirect() throws Exception {
        TestWebServer.Handler handler = request ->
                new MockResponse().setResponseCode(302).setHeader("Location", "/redirected");
        try (TestWebServer server = new TestWebServer(19885, handler);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19885/mcp")).GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(302, response.statusCode());
        }
    }

    @Test
    void nonRedirectResponsePassesThroughUnchanged() throws Exception {
        TestWebServer.Handler handler = request ->
                new MockResponse().setResponseCode(200).setBody("hello").setHeader("X-Test", "value");
        try (TestWebServer server = new TestWebServer(19886, handler);
                HttpClient delegate = HttpClient.newBuilder().build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);
            HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:19886/mcp")).GET().build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            assertEquals(200, response.statusCode());
            assertEquals("hello", response.body());
            assertEquals("value", response.headers().firstValue("X-Test").orElse(null));
        }
    }

    @Test
    void followRedirectsReportsNormal_otherGettersDelegate() {
        try (HttpClient delegate = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()) {
            HttpClient client = new RedirectSafeHttpClient(delegate);

            assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects());
            assertEquals(HttpClient.Version.HTTP_1_1, client.version());
            assertEquals(delegate.connectTimeout(), client.connectTimeout());
        }
    }

    @Test
    void closeTerminatesTheDelegate() {
        HttpClient delegate = HttpClient.newBuilder().build();
        HttpClient client = new RedirectSafeHttpClient(delegate);

        client.close();

        assertTrue(delegate.isTerminated());
    }
}
