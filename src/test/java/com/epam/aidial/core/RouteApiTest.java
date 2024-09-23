package com.epam.aidial.core;

import io.vertx.core.Vertx;
import io.vertx.core.http.HttpMethod;
import io.vertx.junit5.VertxExtension;
import io.vertx.junit5.VertxTestContext;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(VertxExtension.class)
class RouteApiTest extends ResourceBaseTest {

    @ParameterizedTest
    @MethodSource("datasource")
    void route(HttpMethod method, String path, String apiKey, int expectedStatus, String expectedResponse,
               Vertx vertx, VertxTestContext context) {

        var targetServer = vertx.createHttpServer()
                .requestHandler(req -> req.response().end(req.path()));
        targetServer.listen(9876)
                .onComplete(context.succeedingThenComplete());

        var reqBody = method == HttpMethod.POST ? UUID.randomUUID().toString() : null;
        var resp = send(method, path, null, reqBody, "api-key", apiKey);

        assertEquals(expectedStatus, resp.status());
        assertEquals(expectedResponse, resp.body());
    }

    private static List<Arguments> datasource() {
        return List.of(
                Arguments.of(HttpMethod.GET, "/v1/plain", "vstore_user_key", 200, "/"),
                Arguments.of(HttpMethod.GET, "/v1/plain", "vstore_admin_key", 200, "/"),
                Arguments.of(HttpMethod.GET, "/v1/vector_store/1", "vstore_user_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.GET, "/v1/vector_store/1", "vstore_admin_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.POST, "/v1/vector_store/1", "vstore_user_key", 403, "Forbidden route"),
                Arguments.of(HttpMethod.POST, "/v1/vector_store/1", "vstore_admin_key", 200, "/v1/vector_store/1")
        );
    }
}
