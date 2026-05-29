package com.epam.aidial.cli.http;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
        assertEquals(0, CliHttpClient.toExitCode(201));
        assertEquals(0, CliHttpClient.toExitCode(204));
        assertEquals(2, CliHttpClient.toExitCode(400));
        assertEquals(2, CliHttpClient.toExitCode(422));
        assertEquals(3, CliHttpClient.toExitCode(401));
        assertEquals(3, CliHttpClient.toExitCode(403));
        assertEquals(4, CliHttpClient.toExitCode(404));
        assertEquals(5, CliHttpClient.toExitCode(409));
        assertEquals(6, CliHttpClient.toExitCode(412));
        assertEquals(1, CliHttpClient.toExitCode(500));
        assertEquals(1, CliHttpClient.toExitCode(0));
    }

    @Test
    void postReturnsResponseBodyStatusAndEtag() {
        server.createContext("/v1/models/public/m", exchange -> {
            exchange.getResponseHeaders().add("ETag", "\"abc123\"");
            send(exchange, 201, "{\"name\":\"m\"}");
        });

        CliHttpClient.Response r = new CliHttpClient(baseUrl, "k").post(
                "/v1/models/public/m", "{\"endpoint\":\"http://x\"}");

        assertEquals(201, r.status());
        assertEquals("{\"name\":\"m\"}", r.body());
        assertEquals("\"abc123\"", r.etag());
    }

    @Test
    void postSendsApiKeyAndContentTypeHeadersAndBody() {
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("Api-Key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            send(exchange, 201, "{}");
        });

        new CliHttpClient(baseUrl, "the-key").post("/v1/models/public/m", "{\"endpoint\":\"http://x\"}");

        assertEquals("the-key", apiKeyHeader.get());
        assertEquals("application/json", contentType.get());
        assertEquals("{\"endpoint\":\"http://x\"}", capturedBody.get());
    }

    @Test
    void putSendsApiKeyContentTypeBodyAndIfMatchHeader() {
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<String> ifMatch = new AtomicReference<>();
        AtomicReference<String> capturedBody = new AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("Api-Key"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.getResponseHeaders().add("ETag", "\"new\"");
            send(exchange, 200, "{\"name\":\"m\"}");
        });

        CliHttpClient.Response r = new CliHttpClient(baseUrl, "the-key").put(
                "/v1/models/public/m", "{\"endpoint\":\"http://x\"}", Map.of("If-Match", "\"old\""));

        assertEquals(200, r.status());
        assertEquals("\"new\"", r.etag());
        assertEquals("the-key", apiKeyHeader.get());
        assertEquals("application/json", contentType.get());
        assertEquals("\"old\"", ifMatch.get());
        assertEquals("{\"endpoint\":\"http://x\"}", capturedBody.get());
    }

    @Test
    void deleteSendsApiKeyAndIfMatchHeader() {
        AtomicReference<String> apiKeyHeader = new AtomicReference<>();
        AtomicReference<String> ifMatch = new AtomicReference<>();
        AtomicReference<String> method = new AtomicReference<>();
        server.createContext("/v1/models/public/m", exchange -> {
            apiKeyHeader.set(exchange.getRequestHeaders().getFirst("Api-Key"));
            ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
            method.set(exchange.getRequestMethod());
            send(exchange, 204, "");
        });

        CliHttpClient.Response r = new CliHttpClient(baseUrl, "the-key").delete("/v1/models/public/m", Map.of("If-Match", "\"v1\""));

        assertEquals(204, r.status());
        assertEquals("DELETE", method.get());
        assertEquals("the-key", apiKeyHeader.get());
        assertEquals("\"v1\"", ifMatch.get());
    }

    @Test
    void deleteOmitsHeadersWithNullOrBlankValues() {
        AtomicReference<String> ifMatch = new AtomicReference<>("present");
        server.createContext("/v1/models/public/m", exchange -> {
            ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
            send(exchange, 204, "");
        });

        Map<String, String> nullValue = new java.util.HashMap<>();
        nullValue.put("If-Match", null);
        new CliHttpClient(baseUrl, "k").delete("/v1/models/public/m", nullValue);
        assertNull(ifMatch.get());

        new CliHttpClient(baseUrl, "k").delete("/v1/models/public/m", Map.of("If-Match", "  "));
        assertNull(ifMatch.get());
    }

    @Test
    void putOmitsHeadersWithNullOrBlankValues() {
        AtomicReference<String> ifMatch = new AtomicReference<>("present");
        server.createContext("/v1/models/public/m", exchange -> {
            ifMatch.set(exchange.getRequestHeaders().getFirst("If-Match"));
            send(exchange, 200, "{}");
        });

        Map<String, String> nullValue = new java.util.HashMap<>();
        nullValue.put("If-Match", null);
        new CliHttpClient(baseUrl, "k").put("/v1/models/public/m", "{}", nullValue);
        assertNull(ifMatch.get());

        new CliHttpClient(baseUrl, "k").put("/v1/models/public/m", "{}", Map.of("If-Match", "  "));
        assertNull(ifMatch.get());
    }

    @Test
    void getEncodesSpecialCharsInPath() {
        AtomicReference<String> requestPath = new AtomicReference<>();
        server.createContext("/v1/models/public/gpt#4 plus", exchange -> {
            requestPath.set(exchange.getRequestURI().getRawPath());
            send(exchange, 200, "{}");
        });

        new CliHttpClient(baseUrl, "k").get("/v1/models/public/gpt#4 plus");

        assertEquals("/v1/models/public/gpt%234%20plus", requestPath.get());
    }

    @Test
    void getWithQueryAppendsQuerySeparately() {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server.createContext("/v1/models/public/", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, 200, "{\"items\":[]}");
        });

        new CliHttpClient(baseUrl, "k").get("/v1/models/public/", "limit=100");

        assertEquals("limit=100", rawQuery.get());
    }

    @Test
    void getEncodesSpacesInQueryValues() {
        AtomicReference<String> rawQuery = new AtomicReference<>();
        server.createContext("/v1/models/public/", exchange -> {
            rawQuery.set(exchange.getRequestURI().getRawQuery());
            send(exchange, 200, "{\"items\":[]}");
        });

        new CliHttpClient(baseUrl, "k").get("/v1/models/public/", "name=my model&limit=100");

        assertEquals("name=my%20model&limit=100", rawQuery.get());
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
