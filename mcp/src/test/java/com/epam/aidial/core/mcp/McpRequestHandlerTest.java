package com.epam.aidial.core.mcp;

import com.epam.aidial.core.mcp.transport.VertxMcpTransportProvider;
import io.vertx.core.Future;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the deployment-ready latch in {@link McpRequestHandler}: dispatch must defer
 * until the deployment future resolves, with a bounded 2-second wait that produces 503
 * on timeout — matching the pre-existing {@code sessionFactory == null} shape in
 * {@link VertxMcpTransportProvider}.
 */
class McpRequestHandlerTest {

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;
    private int port;

    @BeforeEach
    void setUp() throws Exception {
        vertx = Vertx.vertx();
        client = vertx.createHttpClient();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (server != null) {
            server.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (client != null) {
            client.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
        if (vertx != null) {
            vertx.close().toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    void dispatchesImmediatelyWhenReadyFutureAlreadySucceeded() throws Exception {
        AtomicInteger dispatchCount = new AtomicInteger();
        VertxMcpTransportProvider stub = stubReadingBody(dispatchCount, new AtomicReference<>());
        McpRequestHandler handler = new McpRequestHandler(stub, vertx, Future.succeededFuture());

        bind(handler);
        int status = send().statusCode();

        assertEquals(204, status);
        assertEquals(1, dispatchCount.get());
    }

    @Test
    void returns503WhenReadyFutureAlreadyFailed() throws Exception {
        AtomicInteger dispatchCount = new AtomicInteger();
        VertxMcpTransportProvider stub = stubReadingBody(dispatchCount, new AtomicReference<>());
        McpRequestHandler handler = new McpRequestHandler(
                stub, vertx, Future.failedFuture(new RuntimeException("deploy failed")));

        bind(handler);
        int status = send().statusCode();

        assertEquals(503, status);
        assertEquals(0, dispatchCount.get());
    }

    @Test
    void waitsForLatchThenDispatchesWhenDeploymentCompletesLater() throws Exception {
        AtomicInteger dispatchCount = new AtomicInteger();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        VertxMcpTransportProvider stub = stubReadingBody(dispatchCount, capturedBody);
        Promise<Void> ready = Promise.promise();
        McpRequestHandler handler = new McpRequestHandler(stub, vertx, ready.future());

        bind(handler);
        // Send a non-trivial body that flows in while the latch is unresolved — pins the
        // pause/resume contract: body data must survive the wait and reach the transport's
        // bodyHandler once it is installed by dispatch().
        Future<HttpClientResponse> pending = sendAsync("hello-from-latch");
        Thread.sleep(150);
        assertEquals(0, dispatchCount.get());

        ready.complete();
        int status = pending.toCompletionStage().toCompletableFuture().get(5, TimeUnit.SECONDS).statusCode();

        assertEquals(204, status);
        assertEquals(1, dispatchCount.get());
        assertEquals("hello-from-latch", capturedBody.get());
    }

    @Test
    void returns503WhenLatchTimesOut() throws Exception {
        AtomicInteger dispatchCount = new AtomicInteger();
        VertxMcpTransportProvider stub = stubReadingBody(dispatchCount, new AtomicReference<>());
        // Promise that is never completed — exercises the bounded-wait timeout path.
        McpRequestHandler handler = new McpRequestHandler(stub, vertx, Promise.<Void>promise().future());

        bind(handler);
        long start = System.currentTimeMillis();
        int status = send().statusCode();
        long elapsed = System.currentTimeMillis() - start;

        assertEquals(503, status);
        assertEquals(0, dispatchCount.get());
        // Bound asserted as ≥ 1.5s to allow for scheduler jitter while still proving the latch waited.
        org.junit.jupiter.api.Assertions.assertTrue(elapsed >= 1500,
                "expected the handler to wait near the 2s bound, observed " + elapsed + "ms");
    }

    private VertxMcpTransportProvider stubReadingBody(AtomicInteger counter, AtomicReference<String> body) {
        return new VertxMcpTransportProvider(vertx) {
            @Override
            public void handleRequest(HttpServerRequest request) {
                counter.incrementAndGet();
                request.bodyHandler(buf -> {
                    body.set(buf.toString());
                    request.response().setStatusCode(204).end();
                });
            }
        };
    }

    private void bind(McpRequestHandler handler) throws Exception {
        server = vertx.createHttpServer()
                .requestHandler(handler)
                .listen(0)
                .toCompletionStage()
                .toCompletableFuture()
                .get(5, TimeUnit.SECONDS);
        port = server.actualPort();
    }

    private HttpClientResponse send() throws Exception {
        return sendAsync("{}").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    private Future<HttpClientResponse> sendAsync(String body) {
        return client.request(HttpMethod.POST, port, "localhost", "/mcp")
                .compose(req -> req.send(body));
    }
}
