package com.epam.aidial.core.mcp.client;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DialClientTest {

    private Vertx vertx;
    private HttpServer stubServer;
    private int stubPort;

    private final AtomicReference<HttpMethod> capturedMethod = new AtomicReference<>();
    private final AtomicReference<String> capturedPath = new AtomicReference<>();
    private final Map<String, String> capturedHeaders = new ConcurrentHashMap<>();
    private final AtomicReference<String> capturedBody = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        stubServer = vertx.createHttpServer()
                .requestHandler(this::handleStub)
                .listen(0)
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        stubPort = stubServer.actualPort();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (stubServer != null) {
            stubServer.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    private void handleStub(HttpServerRequest request) {
        capturedMethod.set(request.method());
        capturedPath.set(request.path());
        request.headers().forEach(entry -> capturedHeaders.put(entry.getKey(), entry.getValue()));
        request.bodyHandler(body -> {
            capturedBody.set(body.toString());
            request.response()
                    .setStatusCode(200)
                    .putHeader("Content-Type", "application/json")
                    .end(Buffer.buffer("{\"ok\":true}"));
        });
    }

    @Test
    void forwardsMethodPathHeadersAndBody() throws Exception {
        DialClient client = new DialClient(vertx, vertx.getOrCreateContext(), "http://localhost:" + stubPort);

        DialResponse response = client.request(
                        HttpMethod.POST,
                        "/v1/echo",
                        Map.of("api-key", "test-key", "Authorization", "Bearer xyz"),
                        Map.of("X-Trace-Id", "trace-1", "X-Request-Id", "req-2"),
                        "{\"hello\":\"world\"}")
                .toFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
        assertEquals("{\"ok\":true}", response.body());
        assertNotNull(response.headers());

        assertEquals(HttpMethod.POST, capturedMethod.get());
        assertEquals("/v1/echo", capturedPath.get());
        assertEquals("test-key", capturedHeaders.get("api-key"));
        assertEquals("Bearer xyz", capturedHeaders.get("Authorization"));
        assertEquals("trace-1", capturedHeaders.get("X-Trace-Id"));
        assertEquals("req-2", capturedHeaders.get("X-Request-Id"));
        assertEquals("{\"hello\":\"world\"}", capturedBody.get());
    }

    @Test
    void getWithoutBodyReturnsResponse() throws Exception {
        DialClient client = new DialClient(vertx, vertx.getOrCreateContext(), "http://localhost:" + stubPort);

        DialResponse response = client.request(
                        HttpMethod.GET,
                        "/v1/bucket",
                        Map.of("api-key", "k"),
                        Map.of(),
                        null)
                .toFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
        assertNotNull(response.body());
        assertNotNull(response.headers());
        assertEquals(HttpMethod.GET, capturedMethod.get());
        assertEquals("/v1/bucket", capturedPath.get());
    }

    @Test
    void headersAreCopiedFromResponse() throws Exception {
        DialClient client = new DialClient(vertx, vertx.getOrCreateContext(), "http://localhost:" + stubPort);

        DialResponse response = client.request(
                        HttpMethod.GET,
                        "/v1/bucket",
                        Map.of("api-key", "k"),
                        Map.of(),
                        null)
                .toFuture()
                .get(5, TimeUnit.SECONDS);

        assertNotNull(response.headers());
        assertEquals("application/json", response.headers().get("Content-Type"));
    }

    @Test
    void networkFailurePropagatesAsMonoError() {
        // Port 1 is reserved/unbindable on Linux for unprivileged processes; connection fails.
        DialClient client = new DialClient(vertx, vertx.getOrCreateContext(), "http://localhost:1");

        java.util.concurrent.ExecutionException ex = assertThrows(
                java.util.concurrent.ExecutionException.class,
                () -> client.request(HttpMethod.GET, "/v1/bucket", Map.of(), Map.of(), null)
                        .toFuture()
                        .get(5, TimeUnit.SECONDS));
        assertNotNull(ex.getCause());
    }

    @Test
    void subscribeFromForeignThreadDoesNotBreakBridge() throws Exception {
        DialClient client = new DialClient(vertx, vertx.getOrCreateContext(), "http://localhost:" + stubPort);

        DialResponse response = client.request(
                        HttpMethod.GET,
                        "/v1/bucket",
                        Map.of("api-key", "k"),
                        Map.of(),
                        null)
                .subscribeOn(Schedulers.boundedElastic())
                .toFuture()
                .get(5, TimeUnit.SECONDS);

        assertEquals(200, response.statusCode());
    }
}
