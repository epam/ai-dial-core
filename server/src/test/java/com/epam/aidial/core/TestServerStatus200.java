package com.epam.aidial.core;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;

public class TestServerStatus200 {

    public static void main(String[] args) {
        Vertx vertx = Vertx.vertx();
        HttpServerOptions options = new HttpServerOptions()
                .setCompressionSupported(true);
        HttpServer server = vertx.createHttpServer(options);

        server.connectionHandler(connection -> {
            System.err.println("Accepted connection: " + connection.remoteAddress());
        }).requestHandler(request -> {
            System.err.println("Received request"
                    + ". Protocol: " + request.version().alpnName()
                    + ". Method: " + request.method()
                    + ". Uri: " + request.uri()
                    + ". Headers:" + request.headers());

            request.body()
                    .onSuccess(body -> request.response().setStatusCode(200).end("""
                            {
                              "id": "chatcmpl-7VfMTgj3ljKdGKS2BEIwloII3IoO0",
                              "object": "chat.completion",
                              "created": 1687781517,
                              "model": "gpt-35-turbo",
                              "choices": [
                                {
                                  "index": 0,
                                  "finish_reason": "stop",
                                  "message": {
                                    "role": "assistant",
                                    "content": "How can I help you today?"
                                  }
                                }
                              ],
                              "usage": {
                                "completion_tokens": 33,
                                "prompt_tokens": 19,
                                "total_tokens": 52
                              }
                            }"""))
                    .onFailure(error -> request.response().setStatusCode(500).end("ERROR"));
        }).listen(7001);
    }

}
