package com.epam.aidial.core.server;

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

    private final ConcurrentHashMap<Key, Handler> mapping = new ConcurrentHashMap<>();
    private final MockWebServer server;
    private final Handler fallback;

    TestWebServer(int port) {
        this(port, Handler.NO_MAPPING);
    }

    @SneakyThrows
    TestWebServer(int port, Handler fallback) {
        this.fallback = fallback;
        this.server = new MockWebServer();
        this.server.setDispatcher(new Router());
        this.server.start(port);
        log.info("TestWebServer started on {}:{}", server.getHostName(), server.getPort());
    }

    @Override
    @SneakyThrows
    public void close() {
        server.close();
        log.info("TestWebServer stopped");
    }

    public void map(HttpMethod method, String path, Handler handler) {
        Key key = new Key(method.name(), path);
        mapping.put(key, handler);
    }

    public void map(HttpMethod method, String path, MockResponse response) {
        map(method, path, request -> response);
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
        Handler handler = mapping.getOrDefault(key, fallback);
        MockResponse response = handler.map(request);

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

    public interface Handler {
        Handler NO_MAPPING = request -> createResponse(500, "No mapping");

        MockResponse map(RecordedRequest request);
    }

    private class Router extends Dispatcher {
        @NotNull
        @Override
        public MockResponse dispatch(@NotNull RecordedRequest request) {
            return onRequest(request);
        }
    }
}
