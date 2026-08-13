package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests locking the short-name-addressing contract: the {@code id}/{@code model}
 * fields on the legacy {@code /openai/models} and {@code /openai/deployments} listings surface
 * the short name (last path segment) for API-managed entries, matching file-sourced entries —
 * never the canonical id. The admin Configuration API ({@code /v1/{type}/{bucket}/...}) GET is
 * blob-storage-backed directly (not the in-memory map), and also projects the short name — the
 * URL's own {@code name} segment — for the same reason.
 */
public class CanonicalIdListingTest extends ResourceBaseTest {

    private static final String API_MODEL_BODY = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/canonical-test/chat/completions"
            }
            """;

    @Test
    void testApiManagedModelSurfacedAsShortNameInOpenAiModels() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/canonical-test", null, API_MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response list = send(HttpMethod.GET, "/openai/models", null, "");
        verify(list, 200);
        assertTrue(list.body().contains("\"id\":\"canonical-test\""),
                () -> "Expected short name for API-managed model: " + list.body());
        assertTrue(list.body().contains("\"model\":\"canonical-test\""),
                () -> "Expected short name model field for API-managed model: " + list.body());
        assertFalse(list.body().contains("models/platform/canonical-test"),
                () -> "Canonical id must not leak into the outbound listing: " + list.body());
    }

    @Test
    void testApiManagedModelSurfacedAsShortNameInOpenAiDeployments() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/canonical-deployments", null, API_MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response list = send(HttpMethod.GET, "/openai/deployments", null, "");
        verify(list, 200);
        assertTrue(list.body().contains("\"id\":\"canonical-deployments\""),
                () -> "Expected short name for API-managed deployment: " + list.body());
        assertFalse(list.body().contains("models/platform/canonical-deployments"),
                () -> "Canonical id must not leak into the outbound listing: " + list.body());
    }

    @Test
    void testFileSourcedModelStillSurfacedAsSimpleName() {
        Response list = send(HttpMethod.GET, "/openai/models", null, "");
        verify(list, 200);
        // File-sourced model defined in server/src/test/resources/aidial.config.json keeps simple-name keying.
        assertTrue(list.body().contains("\"id\":\"chat-gpt-35-turbo\""),
                () -> "Expected simple name for file-sourced model: " + list.body());
        assertTrue(list.body().contains("\"id\":\"embedding-ada\""),
                () -> "Expected simple name for file-sourced model: " + list.body());
    }

    @Test
    void testApiManagedModelAdminGetProjectsShortName() {
        // Admin GET reads blob storage directly by descriptor (not the in-memory map — see
        // short-name-keyed-config-maps.md), so it projects the URL's own short-name segment,
        // matching how the entity is keyed in Config for runtime resolution.
        verify(send(HttpMethod.PUT, "/v1/models/platform/admin-listing-projection", null, API_MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response single = send(HttpMethod.GET, "/v1/models/platform/admin-listing-projection", null, "",
                "authorization", "admin");
        verify(single, 200);
        assertTrue(single.body().contains("\"name\":\"admin-listing-projection\""),
                () -> "Admin GET must project the short name for API entries: " + single.body());
    }
}
