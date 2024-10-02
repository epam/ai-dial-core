package com.epam.aidial.core;

import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import lombok.SneakyThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

class TestWebServer implements AutoCloseable {

    private final ConcurrentHashMap<Key, Mapper> mapping = new ConcurrentHashMap<>();
    private final HttpServer server;

    @SneakyThrows
    TestWebServer(int port) {
        CompletableFuture<HttpServer> future = new CompletableFuture<>();

        Vertx.vertx().createHttpServer()
                .requestHandler(request -> request.body()
                        .onSuccess(body -> onRequest(request, body))
                        .onFailure(error -> onError(request, error))).listen(port)
                .onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        server = future.get(10, TimeUnit.SECONDS);
        System.out.println("[Test Web Server] Started at " + port);
    }

    @Override
    @SneakyThrows
    public void close() {
        CompletableFuture<Void> future = new CompletableFuture<>();

        server.close().onSuccess(future::complete)
                .onFailure(future::completeExceptionally);

        future.get(10, TimeUnit.SECONDS);
        System.out.println("[Test Web Server] Stopped");
    }

    public void map(HttpMethod method, String path, Mapper mapper) {
        mapping.put(new Key(method, path), mapper);
    }

    public void map(HttpMethod method, String path, int status) {
        map(method, path, status, "");
    }

    public void map(HttpMethod method, String path, int status, String answer) {
        map(method, path, (request, body1) -> request.response().setStatusCode(200).end(answer));
    }

    private void onRequest(HttpServerRequest request, Buffer body) {
        System.out.println("[Test Web Server] Received request. Method: " + request.method()
                           + ". Path: " + request.path() + ". Headers: " + request.headers().entries() + ". Body: " + body);

        Mapper mapper = mapping.getOrDefault(new Key(request.method(), request.path()), Mapper.DEFAULT);
        mapper.map(request, body.toString());
    }

    private void onError(HttpServerRequest request, Throwable error) {
        System.err.println(error.getMessage());
        request.response().setStatusCode(500).end();
    }

    public interface Mapper {

        Mapper DEFAULT = (request, body) -> request.response().setStatusCode(500).end();

        void map(HttpServerRequest request, String body);
    }

    private record Key(HttpMethod method, String path) {
    }
}
