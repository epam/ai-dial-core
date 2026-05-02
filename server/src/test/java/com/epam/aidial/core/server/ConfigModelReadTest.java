package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.1: GET /v1/models/public/{name} read path.
 * Asserts the unified-config response shape — entity-intrinsic fields top-level,
 * always-present {@code "status": "valid"}, admin-only {@code "source": "file"}.
 */
public class ConfigModelReadTest extends ResourceBaseTest {

    @Test
    void testAdminSeesStatusAndSource() {
        Response response = send(HttpMethod.GET, "/v1/models/public/test-model-v1", null, "",
                "authorization", "admin");
        verify(response, 200);
        String body = response.body();
        assertTrue(body.contains("\"status\":\"valid\""), () -> "Missing status=valid: " + body);
        assertTrue(body.contains("\"source\":\"file\""), () -> "Missing source=file: " + body);
        assertTrue(body.contains("\"name\":\"test-model-v1\""), () -> "Missing name: " + body);
    }

    @Test
    void testUserSeesStatusButNotSource() {
        Response response = send(HttpMethod.GET, "/v1/models/public/test-model-v1", null, "",
                "authorization", "user");
        verify(response, 200);
        String body = response.body();
        assertTrue(body.contains("\"status\":\"valid\""), () -> "Missing status=valid: " + body);
        assertTrue(body.contains("\"name\":\"test-model-v1\""), () -> "Missing name: " + body);
        assertFalse(body.contains("\"source\""), () -> "source must not appear for non-admin: " + body);
    }

    @Test
    void testApiKeySeesStatusButNotSource() {
        // Default api-key proxyKey1 is auto-injected by ResourceBaseTest.send() — role "default", not admin.
        Response response = send(HttpMethod.GET, "/v1/models/public/test-model-v1");
        verify(response, 200);
        String body = response.body();
        assertTrue(body.contains("\"status\":\"valid\""), () -> "Missing status=valid: " + body);
        assertFalse(body.contains("\"source\""), () -> "source must not appear for non-admin: " + body);
    }

    @Test
    void testMissingModelReturns404() {
        Response response = send(HttpMethod.GET, "/v1/models/public/no-such-model", null, "",
                "authorization", "user");
        verify(response, 404);
    }

    @Test
    void testEndpointVisibleToPublicView() {
        Response response = send(HttpMethod.GET, "/v1/models/public/chat-gpt-35-turbo", null, "",
                "authorization", "user");
        verify(response, 200);
        assertTrue(response.body().contains("endpoint"),
                () -> "Expected endpoint field for public view: " + response.body());
    }
}
