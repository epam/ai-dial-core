package com.epam.aidial.core.server;

import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.http.HttpMethod;
import okhttp3.mockwebserver.MockResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void routeRateLimited() {
        String path = "/rate_limited_route";
        String[] headers = new String[]{"api-key", "vstore_user_key"};

        try (TestWebServer server = new TestWebServer(9876, req -> new MockResponse())) {
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

        try (TestWebServer server = new TestWebServer(9876, req -> new MockResponse())) {
            Response response = send(HttpMethod.GET, path, null, null, headers);
            assertTrue(response.ok());

            response = send(HttpMethod.GET, path, null, null, headers);
            assertTrue(response.ok());
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
                Arguments.of(HttpMethod.POST, "/v1/rate", "vstore_user_key", 200, "OK")
        );
    }
}
