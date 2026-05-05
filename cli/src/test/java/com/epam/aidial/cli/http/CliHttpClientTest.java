package com.epam.aidial.cli.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CliHttpClientTest {

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.start();
        baseUrl = "http://localhost:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void getReturnsResponseBodyAndStatus() {
        respond("/v1/models/public/gpt-4", 200, "{\"name\":\"gpt-4\"}");

        CliHttpClient.Response r = new CliHttpClient(baseUrl, "secret").get("/v1/models/public/gpt-4");

        assertEquals(200, r.status());
        assertEquals("{\"name\":\"gpt-4\"}", r.body());
    }

    @Test
    void getSendsApiKeyAndAcceptHeaders() {
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> acceptHeader = new AtomicReference<>();
        server.createContext("/v1/models/public/x", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("Api-Key"));
            acceptHeader.set(exchange.getRequestHeaders().getFirst("Accept"));
            send(exchange, 200, "{}");
        });

        new CliHttpClient(baseUrl, "the-key").get("/v1/models/public/x");

        assertEquals("the-key", apiKeyHeader.get());
        assertEquals("application/json", acceptHeader.get());
    }

    @Test
    void getReturnsErrorStatusBody() {
        respond("/v1/models/public/missing", 404, "{\"error\":\"not found\"}");

        CliHttpClient.Response r = new CliHttpClient(baseUrl, "k").get("/v1/models/public/missing");

        assertEquals(404, r.status());
        assertEquals("{\"error\":\"not found\"}", r.body());
    }

    @Test
    void networkErrorThrowsWrapped() {
        server.stop(0);
        CliHttpClient client = new CliHttpClient(baseUrl, "k");

        CliHttpClient.NetworkException ex = assertThrows(
                CliHttpClient.NetworkException.class,
                () -> client.get("/v1/models/public/x"));
        org.junit.jupiter.api.Assertions.assertTrue(ex.getMessage().contains("Network error"));
    }

    @Test
    void toExitCodeMappings() {
        assertEquals(0, CliHttpClient.toExitCode(200));
        assertEquals(0, CliHttpClient.toExitCode(204));
        assertEquals(3, CliHttpClient.toExitCode(401));
        assertEquals(3, CliHttpClient.toExitCode(403));
        assertEquals(4, CliHttpClient.toExitCode(404));
        assertEquals(1, CliHttpClient.toExitCode(500));
        assertEquals(1, CliHttpClient.toExitCode(0));
    }

    private void respond(String path, int status, String body) {
        server.createContext(path, exchange -> send(exchange, status, body));
    }

    private static void send(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
