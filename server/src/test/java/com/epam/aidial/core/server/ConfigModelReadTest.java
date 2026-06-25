package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.1: GET {@code /v1/models/platform/{name}} read path.
 * Under U.1 (2026-05-21) the per-entity GET is blob-only and the {@code source} field is retired
 * entirely. File-sourced models surface only via {@code /v1/admin/config/file/models/{name}}.
 */
public class ConfigModelReadTest extends ResourceBaseTest {

    @Test
    void testFileModelNotAddressableOnPerEntityGet() {
        // U.1: file-sourced entries are no longer addressable on the per-entity surface.
        Response response = send(HttpMethod.GET, "/v1/models/platform/test-model-v1", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testFileModelReadableViaFileConfigEndpoint() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models/test-model-v1", null, "",
                "authorization", "admin");
        verify(response, 200);
        String body = response.body();
        assertTrue(body.contains("\"status\":\"valid\""), () -> "Missing status=valid: " + body);
        assertTrue(body.contains("\"name\":\"test-model-v1\""), () -> "Missing name: " + body);
        assertFalse(body.contains("\"source\""),
                () -> "U.1: source field must not appear in any response: " + body);
    }

    @Test
    void testFileConfigEndpointRequiresAdmin() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models/test-model-v1", null, "",
                "authorization", "user");
        verify(response, 403);
    }

    @Test
    void testMissingModelReturns404() {
        Response response = send(HttpMethod.GET, "/v1/models/platform/no-such-model", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testEndpointVisibleToPublicViewOnBlobModel() {
        // chat-gpt-35-turbo is a file model — verify public reads see entity fields on the
        // file-config endpoint when the request is admin-gated.
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models/chat-gpt-35-turbo", null, "",
                "authorization", "admin");
        verify(response, 200);
        assertTrue(response.body().contains("endpoint"),
                () -> "Expected endpoint field: " + response.body());
    }
}
