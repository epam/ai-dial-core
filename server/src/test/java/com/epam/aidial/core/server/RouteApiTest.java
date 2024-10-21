package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RouteApiTest extends ResourceBaseTest {

    @ParameterizedTest
    @MethodSource("datasource")
    void route(HttpMethod method, String path, String apiKey, int expectedStatus, String expectedResponse) {
        TestWebServer.Handler handler = request -> new MockResponse().setBody(request.getPath());
        try (TestWebServer server = new TestWebServer(9876, handler)) {
            String reqBody = (method == HttpMethod.POST) ? UUID.randomUUID().toString() : null;
            Response resp = send(method, path, null, reqBody, "api-key", apiKey);

            assertEquals(expectedStatus, resp.status());
            assertEquals(expectedResponse, resp.body());
        }
    }

    private static List<Arguments> datasource() {
        return List.of(
                Arguments.of(HttpMethod.GET, "/v1/plain", "vstore_user_key", 200, "/"),
                Arguments.of(HttpMethod.GET, "/v1/plain", "vstore_admin_key", 200, "/"),
                Arguments.of(HttpMethod.GET, "/v1/vector_store/1", "vstore_user_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.GET, "/v1/vector_store/1", "vstore_admin_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.HEAD, "/v1/vector_store/1", "vstore_user_key", 200, null),
                Arguments.of(HttpMethod.HEAD, "/v1/vector_store/1", "vstore_admin_key", 200, null),
                Arguments.of(HttpMethod.POST, "/v1/vector_store/1", "vstore_user_key", 403, "Forbidden route"),
                Arguments.of(HttpMethod.POST, "/v1/vector_store/1", "vstore_admin_key", 200, "/v1/vector_store/1"),
                Arguments.of(HttpMethod.GET, "/v1/forbidden", "vstore_admin_key", 403, "Forbidden route")
        );
    }
}
