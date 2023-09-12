package com.epam.aidial.core;

import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;

public class TestServerStatus429 {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        HttpServer originServer = vertx.createHttpServer();

        originServer.connectionHandler(connection -> {
            System.err.println("Accepted connection: " + connection.remoteAddress());
        }).requestHandler(request -> {
            System.err.println("Received request: " + request.headers());
            request.body().onSuccess(body -> System.err.println("Received body: " + body.length()))
                    .onFailure(error -> System.err.println("Failed to receive body: " + error));

            request.response()
                    .setStatusCode(HttpStatus.TOO_MANY_REQUESTS.getCode())
                    .end("TOO MANY REQUESTS");
        }).listen(7002);
    }

}
