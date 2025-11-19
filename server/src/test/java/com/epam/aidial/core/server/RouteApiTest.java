package com.epam.aidial.core.server;

import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.http.HttpMethod;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RouteApiTest extends ResourceBaseTest {

    @Test
    void testWebSocketCommunication() throws InterruptedException {
        // Create a listener for the client-side WebSocket
        final BlockingQueue<String> clientReceivedMessages = new ArrayBlockingQueue<>(1);
        WebSocketListener clientListener = new WebSocketListener() {

            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull okhttp3.Response response) {
                webSocket.send("Hello from client!");
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable okhttp3.Response response) {
                System.err.println("Client WebSocket failure: " + t.getMessage());
            }

            @Override
            public void onMessage(@NotNull WebSocket webSocket, @NotNull String text) {
                System.out.println("Client received: " + text);
                clientReceivedMessages.add(text);
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                System.out.println("Client WebSocket closing: " + code + " " + reason);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                System.out.println("Client WebSocket closed: " + code + " " + reason);
            }

        };

        // Create a listener for the server-side WebSocket within MockWebServer
        final BlockingQueue<String> serverReceivedMessages = new ArrayBlockingQueue<>(1);
        WebSocketListener serverListener = new WebSocketListener() {
            @Override
            public void onOpen(@NotNull WebSocket webSocket, @NotNull okhttp3.Response response) {
                System.out.println("Server WebSocket opened.");
            }

            @Override
            public void onFailure(@NotNull WebSocket webSocket, @NotNull Throwable t, @Nullable okhttp3.Response response) {
                System.err.println("Server WebSocket failure: " + t.getMessage());
            }

            @Override
            public void onMessage(WebSocket webSocket, @NotNull String text) {
                System.out.println("Server received: " + text);
                serverReceivedMessages.add(text);
                webSocket.send("Hello from server!"); // Respond to the client
                webSocket.close(1000, "client close");
            }

            @Override
            public void onClosing(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                System.out.println("Server WebSocket closing: " + code + " " + reason);
            }

            @Override
            public void onClosed(@NotNull WebSocket webSocket, int code, @NotNull String reason) {
                System.out.println("Server WebSocket closed: " + code + " " + reason);
            }
        };

        try (var mockWebServer = new MockWebServer()) {

            // dispatch request to MockResponse that initiates a WebSocket upgrade
            mockWebServer.setDispatcher(new Dispatcher() {

                @NotNull
                @Override
                public MockResponse dispatch(@NotNull RecordedRequest request) {
                    var mockResponse = new MockResponse();
                    mockResponse.withWebSocketUpgrade(serverListener);
                    return mockResponse;
                }
            });

            mockWebServer.start(9876);

            // Build the client request to the mock WebSocket URL
            Request request = new Request.Builder()
                    .url("ws://localhost:" +  serverPort + "/v1/websocket")
                    .addHeader("api-key", "vstore_user_key")
                    .build();

            var client = new OkHttpClient();


            // Connect the client WebSocket
            client.newWebSocket(request, clientListener);

            // Assert client received message from server
            String clientMessage = clientReceivedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(clientMessage);
            assertEquals("Hello from server!", clientMessage);

            // Assert server received message from client
            String serverMessage = serverReceivedMessages.poll(5, TimeUnit.SECONDS);
            assertNotNull(serverMessage);
            assertEquals("Hello from client!", serverMessage);

            // Close the client WebSocket
            //clientWebSocket.close(1000, "Client closing");
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @ParameterizedTest
    @MethodSource("datasource")
    void route(HttpMethod method, String path, String apiKey, int expectedStatus, String expectedResponse) {
        TestWebServer.Handler handler = request -> {
            assertNotNull(request.getHeader(Proxy.HEADER_API_KEY));
            assertNotNull(request.getPath());
            return new MockResponse().setBody(request.getPath());
        };
        try (TestWebServer ignored = new TestWebServer(9876, handler)) {
            String reqBody = (method == HttpMethod.POST) ? UUID.randomUUID().toString() : null;
            Response resp = send(method, path, null, reqBody, "api-key", apiKey);

            assertEquals(expectedStatus, resp.status());
            assertEquals(expectedResponse, resp.body());
        }
    }

    @Test
    void routeRateLimited() {
        String path = "/rate_limited_route";
        String[] headers = new String[]{"api-key", "vstore_user_key"};

        try (TestWebServer ignored = new TestWebServer(9876, req -> new MockResponse())) {
            Response response = send(HttpMethod.GET, path, null, null, headers);
            assertTrue(response.ok());

            response = send(HttpMethod.GET, path, null, null, headers);
            assertEquals(HttpStatus.TOO_MANY_REQUESTS.getCode(), response.status());
        }
    }

    @Test
    void routeNotRateLimited() {
        String path = "/rate_limited_route";
        String[] headers = new String[]{"api-key", "vstore_admin_key"};

        try (TestWebServer ignored = new TestWebServer(9876, req -> new MockResponse())) {
            Response response = send(HttpMethod.GET, path, null, null, headers);
            assertTrue(response.ok());

            response = send(HttpMethod.GET, path, null, null, headers);
            assertTrue(response.ok());
        }
    }

    @Test
    void route_404() {
        TestWebServer.Handler handler = request -> new MockResponse().setResponseCode(404);
        try (TestWebServer ignored = new TestWebServer(9876, handler)) {
            Response resp = send(HttpMethod.GET, "/v1/plain", null, null, "api-key", "vstore_user_key");
            assertEquals(404, resp.status());
        }
    }

    private static List<Arguments> datasource() {
        return List.of(
                Arguments.of(HttpMethod.GET, "/v1/plain", "vstore_user_key", 200, "/"),
                Arguments.of(HttpMethod.GET, "/v1/plain", "vstore_admin_key", 200, "/"),
                Arguments.of(HttpMethod.GET, "/v1/vector_store/1", "vstore_user_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.GET, "/v1/vector_store/1?q=p", "vstore_user_key", 200, "/v1/vector_store/1?q=p"),
                Arguments.of(HttpMethod.GET, "/v1/vector_store/1", "vstore_admin_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.HEAD, "/v1/vector_store/1", "vstore_user_key", 200, null),
                Arguments.of(HttpMethod.HEAD, "/v1/vector_store/1", "vstore_admin_key", 200, null),
                Arguments.of(HttpMethod.POST, "/v1/vector_store/1", "vstore_user_key", 403, "Forbidden route"),
                Arguments.of(HttpMethod.POST, "/v1/vector_store/1", "vstore_admin_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.GET, "/v1/forbidden", "vstore_admin_key", 403, "Forbidden route"),
                Arguments.of(HttpMethod.GET, "/unexpected", "vstore_user_key", 502, "No route"),
                Arguments.of(HttpMethod.POST, "/v1/rate", "vstore_user_key", 200, "OK"),
                Arguments.of(HttpMethod.POST, "/v1/rate?k1=v1&k2=v2", "vstore_user_key", 200, "OK")
        );
    }
}
