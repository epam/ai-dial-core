package com.epam.aidial.core.server.mcp;

import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpAuthorizationChallengeProviderTest {

    private static final String CHALLENGE =
            "Bearer realm=\"OAuth\", resource_metadata=\"https://example.com/.well-known/oauth-protected-resource/mcp\"";

    private McpHttpClientBuilder httpClientBuilder;
    private HttpServer server;

    @BeforeEach
    void setUp() {
        McpHttpClientBuilder.Settings settings = new McpHttpClientBuilder.Settings();
        settings.setConnectTimeout(2000);
        httpClientBuilder = new McpHttpClientBuilder(settings);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
        httpClientBuilder.close();
    }

    /**
     * A strict server issues its challenge only to a well-formed MCP request - an initialize that
     * accepts event streams - and rejects anything else as malformed, with no pointer.
     */
    @Test
    void drawsTheChallengeFromStrictServer() throws Exception {
        String endpoint = serve(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String accept = exchange.getRequestHeaders().getFirst("Accept");
            if (accept == null || !accept.contains("text/event-stream") || !body.contains("\"method\":\"initialize\"")) {
                respond(exchange, 400, "{\"error\":\"malformed MCP request\"}");
                return;
            }
            exchange.getResponseHeaders().add("WWW-Authenticate", CHALLENGE);
            respond(exchange, 401, "");
        });

        HttpResponse<Void> bareProbe = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(endpoint))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(400, bareProbe.statusCode(), "the fixture must refuse a request that is not a real MCP initialize");

        assertEquals(Optional.of(CHALLENGE), new McpAuthorizationChallengeProvider(httpClientBuilder).challenge(endpoint));
    }

    @Test
    void noChallengeWhenTheServerLetsTheClientIn() throws Exception {
        String endpoint = serve(exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (body.contains("\"method\":\"initialize\"")) {
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                String id = body.replaceAll(".*\"id\":(\"[^\"]*\"|\\d+).*", "$1");
                respond(exchange, 200, "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"protocolVersion\":\"2025-06-18\","
                        + "\"capabilities\":{},\"serverInfo\":{\"name\":\"open\",\"version\":\"1.0\"}}}");
                return;
            }
            respond(exchange, 202, "");
        });

        assertEquals(Optional.empty(), new McpAuthorizationChallengeProvider(httpClientBuilder).challenge(endpoint));
    }

    /** Only a 401 carries a challenge; any other refusal leaves discovery to the well-known locations. */
    @Test
    void noChallengeWhenTheServerRefusesWithoutOne() throws Exception {
        String endpoint = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("WWW-Authenticate", CHALLENGE);
            respond(exchange, 403, "");
        });

        assertEquals(Optional.empty(), new McpAuthorizationChallengeProvider(httpClientBuilder).challenge(endpoint));
    }

    /** A server that answers with an error has still answered: discovery falls back rather than failing. */
    @Test
    void noChallengeWhenTheServerFailsTheRequest() throws Exception {
        String endpoint = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            respond(exchange, 500, "");
        });

        assertEquals(Optional.empty(), new McpAuthorizationChallengeProvider(httpClientBuilder).challenge(endpoint));
    }

    /**
     * A server that sends no response may be the one whose challenge names the right authorization
     * server, so discovery fails instead of falling back without it - and within the timeout.
     */
    @Test
    void failsWhenTheServerSendsNoResponse() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        String endpoint = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            McpAuthorizationChallengeProvider provider = new McpAuthorizationChallengeProvider(httpClientBuilder, Duration.ofSeconds(2));

            HttpException e = assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> assertThrows(HttpException.class, () -> provider.challenge(endpoint)));
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, e.getStatus());
            assertNotNull(ExceptionUtils.throwableOfType(e, HttpTimeoutException.class), "the underlying timeout was dropped");
        } finally {
            released.countDown();
        }
    }

    /**
     * The SDK waits for the initialize handshake under its own initialization timeout, 20s by default. A request
     * timeout at or above that must still decide the outcome: were the SDK's default to fire first, a server that
     * sends no response would fall back silently instead of failing. Short timeouts cannot show this, so this one
     * deliberately runs past the SDK default.
     */
    @Test
    void failsWhenTheServerSendsNoResponseWithinTimeoutAboveTheSdkDefault() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        String endpoint = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            try {
                released.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            McpAuthorizationChallengeProvider provider = new McpAuthorizationChallengeProvider(httpClientBuilder, Duration.ofSeconds(21));

            HttpException e = assertTimeoutPreemptively(Duration.ofSeconds(45),
                    () -> assertThrows(HttpException.class, () -> provider.challenge(endpoint)));
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, e.getStatus());
        } finally {
            released.countDown();
        }
    }

    /**
     * A server that answers and then never completes its reply has still answered - with nothing
     * discovery can use - so it falls back, within the timeout rather than waiting on the stream.
     */
    @Test
    void noChallengeWhenTheReplyNeverCompletes() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        String endpoint = serve(exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write(": open\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            try {
                released.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            McpAuthorizationChallengeProvider provider = new McpAuthorizationChallengeProvider(httpClientBuilder, Duration.ofSeconds(2));

            assertEquals(Optional.empty(), assertTimeoutPreemptively(Duration.ofSeconds(15), () -> provider.challenge(endpoint)));
        } finally {
            released.countDown();
        }
    }

    /**
     * A server that redirects slowly and then sends no response must still fail discovery. If each
     * redirect restarted the request timeout, the chain would outlast the SDK's wait, and the SDK
     * giving up would fall back silently instead.
     */
    @Test
    void failsWhenTheServerRedirectsSlowlyThenSendsNoResponse() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        ExecutorService handlers = Executors.newCachedThreadPool();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(handlers);
        server.createContext("/mcp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            String path = exchange.getRequestURI().getPath();
            String next = switch (path) {
                case "/mcp" -> "/mcp/1";
                case "/mcp/1" -> "/mcp/2";
                case "/mcp/2" -> "/mcp/3";
                case "/mcp/3" -> "/mcp/4";
                default -> null;
            };
            try {
                if (next == null) {
                    released.await(60, TimeUnit.SECONDS);
                    return;
                }
                Thread.sleep(1900);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            exchange.getResponseHeaders().add("Location", next);
            respond(exchange, 307, "");
        });
        server.start();
        try {
            // restarting 2s per hop, four 1.9s redirects plus a silent hop would take 9.6s: well past the SDK's 7s
            Duration requestTimeout = Duration.ofSeconds(2);
            McpAuthorizationChallengeProvider provider = new McpAuthorizationChallengeProvider(httpClientBuilder, requestTimeout);
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";

            HttpException e = assertTimeoutPreemptively(Duration.ofSeconds(30),
                    () -> assertThrows(HttpException.class, () -> provider.challenge(endpoint)));
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, e.getStatus());
        } finally {
            released.countDown();
            handlers.shutdownNow();
        }
    }

    /**
     * A redirect that announces a body and never sends it must not hold the request past its timeout: if the
     * redirect's target then sends no response, discovery still fails rather than falling back silently.
     */
    @Test
    void failsWhenRedirectBodyStallsAndItsTargetSendsNoResponse() throws Exception {
        CountDownLatch released = new CountDownLatch(1);
        try (ServerSocket rawServer = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                while (!rawServer.isClosed()) {
                    try {
                        Socket connection = rawServer.accept();
                        Thread handler = new Thread(() -> redirectWithStalledBodyThenIgnore(connection, released));
                        handler.setDaemon(true);
                        handler.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            String endpoint = "http://127.0.0.1:" + rawServer.getLocalPort() + "/mcp";
            McpAuthorizationChallengeProvider provider = new McpAuthorizationChallengeProvider(httpClientBuilder, Duration.ofSeconds(2));

            HttpException e = assertTimeoutPreemptively(Duration.ofSeconds(15),
                    () -> assertThrows(HttpException.class, () -> provider.challenge(endpoint)));
            assertEquals(HttpStatus.GATEWAY_TIMEOUT, e.getStatus());
        } finally {
            released.countDown();
        }
    }

    private static void redirectWithStalledBodyThenIgnore(Socket connection, CountDownLatch released) {
        try (connection) {
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = in.readLine();
            for (String header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {
                // headers are not needed
            }
            if (requestLine != null && requestLine.startsWith("POST /mcp ")) {
                connection.getOutputStream().write(
                        "HTTP/1.1 307 Temporary Redirect\r\nLocation: /mcp/1\r\nContent-Length: 1000\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                connection.getOutputStream().flush();
            }
            released.await(60, TimeUnit.SECONDS);
        } catch (IOException e) {
            // the client gives up on the connection
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    void failsWhenTheConnectionBreaks() throws Exception {
        try (ServerSocket resetting = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                try (Socket connection = resetting.accept()) {
                    connection.setSoLinger(true, 0);
                    connection.getInputStream().read(new byte[1024]);
                } catch (IOException ignored) {
                    // the socket is torn down on purpose
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            String endpoint = "http://127.0.0.1:" + resetting.getLocalPort() + "/mcp";

            HttpException e = assertThrows(HttpException.class,
                    () -> new McpAuthorizationChallengeProvider(httpClientBuilder).challenge(endpoint));
            assertEquals(HttpStatus.BAD_GATEWAY, e.getStatus());
            assertNotNull(ExceptionUtils.throwableOfType(e, IOException.class), "the underlying I/O failure was dropped");
        }
    }

    /**
     * After a failed initialize the SDK also opens a listening stream. A server that accepts that
     * request and never answers it must not leave it in flight on the shared client: the client has to
     * give up on it and drop the connection once the timeout passes.
     */
    @Test
    void abandonsTheListeningStreamWhenTheServerNeverAnswersIt() throws Exception {
        CountDownLatch listeningStreamAbandoned = new CountDownLatch(1);
        try (ServerSocket rawServer = new ServerSocket(0)) {
            Thread acceptor = new Thread(() -> {
                while (!rawServer.isClosed()) {
                    try {
                        Socket connection = rawServer.accept();
                        Thread handler = new Thread(() -> answerInitializeWithErrorAndIgnoreGet(connection, listeningStreamAbandoned));
                        handler.setDaemon(true);
                        handler.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            });
            acceptor.setDaemon(true);
            acceptor.start();
            String endpoint = "http://127.0.0.1:" + rawServer.getLocalPort() + "/mcp";

            new McpAuthorizationChallengeProvider(httpClientBuilder, Duration.ofSeconds(2)).challenge(endpoint);

            assertTrue(listeningStreamAbandoned.await(15, TimeUnit.SECONDS),
                    "the client kept the unanswered listening stream open");
        }
    }

    private static void answerInitializeWithErrorAndIgnoreGet(Socket connection, CountDownLatch abandoned) {
        try (connection) {
            BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
            String requestLine = in.readLine();
            int contentLength = 0;
            for (String header = in.readLine(); header != null && !header.isEmpty(); header = in.readLine()) {
                if (header.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    contentLength = Integer.parseInt(header.substring("content-length:".length()).trim());
                }
            }
            if (requestLine != null && requestLine.startsWith("GET")) {
                if (in.read() == -1) {
                    abandoned.countDown();
                }
                return;
            }
            in.skip(contentLength);
            connection.getOutputStream().write(
                    "HTTP/1.1 500 Internal Server Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            connection.getOutputStream().flush();
        } catch (IOException e) {
            abandoned.countDown();
        }
    }

    @Test
    void failsWhenTheEndpointRefusesConnections() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String endpoint = "http://127.0.0.1:" + closedPort + "/mcp";

        ConnectException e = assertThrows(ConnectException.class,
                () -> new McpAuthorizationChallengeProvider(httpClientBuilder).challenge(endpoint));
        assertEquals("Cannot connect to " + endpoint, e.getMessage());
    }

    @Test
    void failsWhenTheEndpointHostDoesNotResolve() {
        String endpoint = "http://this-hostname-does-not-exist.invalid/mcp";

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> new McpAuthorizationChallengeProvider(httpClientBuilder).challenge(endpoint));
        assertEquals("Connection failed: The specified endpoint '" + endpoint + "' is invalid or unreachable.", e.getMessage());
    }

    private String serve(HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "");
                return;
            }
            handler.handle(exchange);
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            exchange.getResponseBody().write(bytes);
        }
        exchange.close();
    }
}
