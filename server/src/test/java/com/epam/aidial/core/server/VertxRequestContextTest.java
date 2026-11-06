package com.epam.aidial.core.server;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.impl.ContextInternal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the two Vert.x behaviours {@link ContextManager} relies on instead of a clear step: every HTTP server request
 * runs on its own duplicated context, and the callbacks of an HTTP client call made from it run on that same context.
 * If a Vert.x upgrade changes either, this fails before a stale ProxyContext can show up in another request's logs.
 */
class VertxRequestContextTest {

    private static final String KEY = "marker";

    private final Vertx vertx = Vertx.vertx();
    private final List<Observation> observations = new CopyOnWriteArrayList<>();

    private record Observation(ContextInternal requestContext, Object localBeforeWrite, ContextInternal clientCallbackContext) {
    }

    @AfterEach
    void tearDown() throws Exception {
        vertx.close().toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }

    @Test
    void eachRequestOnOneConnectionRunsOnItsOwnContextAndItsUpstreamCallbacksStayThere() throws Exception {
        HttpServer upstream = listen(vertx.createHttpServer().requestHandler(request -> request.response().end("ok")));
        HttpClient upstreamClient = vertx.createHttpClient();
        HttpServer server = listen(vertx.createHttpServer().requestHandler(request -> {
            ContextInternal requestContext = (ContextInternal) Vertx.currentContext();
            Object localBeforeWrite = requestContext.getLocal(KEY);
            requestContext.putLocal(KEY, request.getHeader("x-marker"));
            upstreamClient.request(new RequestOptions().setHost("127.0.0.1").setPort(upstream.actualPort()).setMethod(HttpMethod.GET))
                    .compose(HttpClientRequest::send)
                    .onComplete(result -> {
                        observations.add(new Observation(requestContext, localBeforeWrite, (ContextInternal) Vertx.currentContext()));
                        request.response().end();
                    });
        }));

        HttpClient oneConnection = vertx.createHttpClient(new HttpClientOptions().setMaxPoolSize(1));
        for (String marker : List.of("first", "second")) {
            int status = oneConnection.request(new RequestOptions().setHost("127.0.0.1").setPort(server.actualPort())
                            .setMethod(HttpMethod.GET).putHeader("x-marker", marker))
                    .compose(HttpClientRequest::send)
                    .compose(response -> response.body().map(response.statusCode()))
                    .toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            assertEquals(200, status);
        }

        assertEquals(2, observations.size());
        Observation first = observations.get(0);
        Observation second = observations.get(1);

        // each request runs on its own duplicate of the one event-loop context
        assertTrue(first.requestContext().isDuplicate());
        assertTrue(second.requestContext().isDuplicate());
        assertNotSame(first.requestContext(), second.requestContext());
        assertSame(first.requestContext().unwrap(), second.requestContext().unwrap());

        // nothing was cleared, yet the second request starts empty, the first keeps its entry, the event loop holds none
        assertNull(first.localBeforeWrite());
        assertNull(second.localBeforeWrite());
        assertEquals("first", first.requestContext().getLocal(KEY));
        assertEquals("second", second.requestContext().getLocal(KEY));
        assertNull(first.requestContext().unwrap().getLocal(KEY));

        // the upstream call's callback ran on the request's own context, so late callbacks see the request's entry
        assertSame(first.requestContext(), first.clientCallbackContext());
        assertSame(second.requestContext(), second.clientCallbackContext());
    }

    private static HttpServer listen(HttpServer server) throws Exception {
        return server.listen(0, "127.0.0.1").toCompletionStage().toCompletableFuture().get(10, TimeUnit.SECONDS);
    }
}
