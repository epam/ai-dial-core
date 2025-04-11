package com.epam.aidial.core.server;

import com.epam.aidial.core.server.controller.TimeController;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerOptions;
import io.vertx.core.json.JsonObject;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@RunWith(VertxUnitRunner.class)
public class TimeControllerTest {

    private Vertx vertx;
    private HttpServer server;
    private HttpClient client;

    @Before
    public void setUp(TestContext context) {
        vertx = Vertx.vertx();
        server = vertx.createHttpServer(new HttpServerOptions().setPort(8080).setHost("localhost"));
        client = vertx.createHttpClient(new HttpClientOptions());

        server.requestHandler(req -> {
            if (req.method() == HttpMethod.GET && "/v1/time".equals(req.path())) {
                TimeController controller = new TimeController(new ProxyContext());
                controller.getCurrentTime().onComplete(ar -> {
                    if (ar.succeeded()) {
                        req.response()
                                .setStatusCode(HttpStatus.OK.getCode())
                                .putHeader("content-type", "application/json")
                                .end(JsonObject.mapFrom(ar.result()).encode());
                    } else {
                        req.response()
                                .setStatusCode(HttpStatus.INTERNAL_SERVER_ERROR.getCode())
                                .end();
                    }
                });
            } else {
                req.response().setStatusCode(HttpStatus.NOT_FOUND.getCode()).end();
            }
        }).listen(context.asyncAssertSuccess());
    }

    @After
    public void tearDown(TestContext context) {
        client.close();
        server.close(context.asyncAssertSuccess());
        vertx.close(context.asyncAssertSuccess());
    }

    @Test
    public void testGetCurrentTime(TestContext context) {
        Async async = context.async();
        HttpClientRequest request = client.get(8080, "localhost", "/v1/time", response -> {
            context.assertEquals(HttpStatus.OK.getCode(), response.statusCode());
            response.bodyHandler(body -> {
                JsonObject json = body.toJsonObject();
                String currentTime = json.getString("currentTime");
                context.assertNotNull(currentTime);
                context.assertTrue(isValidISODateTime(currentTime));
                async.complete();
            });
        });
        request.end();
    }

    private boolean isValidISODateTime(String dateTime) {
        try {
            LocalDateTime.parse(dateTime, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
