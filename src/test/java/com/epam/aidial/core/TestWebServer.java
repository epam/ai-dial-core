package com.epam.aidial.core;

import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
class TestWebServer implements AutoCloseable {

    private static final MockResponse DEFAULT_RESPONSE = createResponse(500, "No mapping");

    private final ConcurrentHashMap<Key, MockResponse> mapping = new ConcurrentHashMap<>();
    private final MockWebServer server;

    @SneakyThrows
    TestWebServer(int port) {
        server = new MockWebServer();
        server.setDispatcher(new Router());
        server.start(port);
        log.info("TestWebServer started");
    }

    @Override
    @SneakyThrows
    public void close() {
        server.close();
        log.info("TestWebServer stopped");
    }

    public void map(HttpMethod method, String path, MockResponse response) {
        Key key = new Key(method.name(), path);
        mapping.put(key, response);
    }

    public void map(HttpMethod method, String path, int status) {
        map(method, path, status, "");
    }

    public void map(HttpMethod method, String path, int status, String body) {
        map(method, path, createResponse(status, body));
    }

    private MockResponse onRequest(RecordedRequest request) {
        log.info("[Test Web Server] Received request. Method: {}. Path: {}. Headers: {}. Body: {}",
                request.getMethod(), request.getPath(), request.getHeaders().toMultimap(), request.getBody());

        Key key = new Key(request.getMethod(), request.getPath());
        MockResponse response = mapping.getOrDefault(key, DEFAULT_RESPONSE);

        log.info("[Test Web Server] Sent response. Status: {}. Body: {}", response.getStatus(), response.getBody());
        return response;
    }

    private static MockResponse createResponse(int status, String body) {
        MockResponse response = new MockResponse();
        response.setResponseCode(status);
        response.setBody(body);
        return response;
    }

    private record Key(String method, String path) {
    }

    private class Router extends Dispatcher {
        @NotNull
        @Override
        public MockResponse dispatch(@NotNull RecordedRequest request) {
            return onRequest(request);
        }
    }
}
