package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 2S.15 + Polish.1 (2026-05-08): canonical IDs surface as the
 * {@code id}/{@code model} fields on the legacy {@code /openai/models} and {@code /openai/deployments}
 * listings for API-managed entries, and on the admin Configuration API
 * ({@code /v1/{type}/{bucket}/...}) GET + listing projection. File-sourced entries continue to
 * surface their simple names. Locks the OQ-23 + Polish.1 contract that clients can copy a
 * listing's identifier verbatim into per-entity URLs.
 */
public class CanonicalIdListingTest extends ResourceBaseTest {

    private static final String API_MODEL_BODY = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/canonical-test/chat/completions"
            }
            """;

    @Test
    void testApiManagedModelSurfacedAsCanonicalIdInOpenAiModels() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/canonical-test", null, API_MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response list = send(HttpMethod.GET, "/openai/models", null, "");
        verify(list, 200);
        assertTrue(list.body().contains("\"id\":\"models/platform/canonical-test\""),
                () -> "Expected canonical id for API-managed model: " + list.body());
        assertTrue(list.body().contains("\"model\":\"models/platform/canonical-test\""),
                () -> "Expected canonical model field for API-managed model: " + list.body());
    }

    @Test
    void testApiManagedModelSurfacedAsCanonicalIdInOpenAiDeployments() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/canonical-deployments", null, API_MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response list = send(HttpMethod.GET, "/openai/deployments", null, "");
        verify(list, 200);
        assertTrue(list.body().contains("\"id\":\"models/platform/canonical-deployments\""),
                () -> "Expected canonical id for API-managed deployment: " + list.body());
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
    void testApiManagedModelAdminGetProjectsCanonicalId() {
        // Polish.1 (2026-05-08): admin GET projects the canonical ID for API-managed entries so
        // operators can copy-paste the identifier verbatim. File-sourced entries keep their simple
        // name. Under U.0 the per-entity GET still projects the canonical ID.
        verify(send(HttpMethod.PUT, "/v1/models/platform/admin-listing-projection", null, API_MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response single = send(HttpMethod.GET, "/v1/models/platform/admin-listing-projection", null, "",
                "authorization", "admin");
        verify(single, 200);
        assertTrue(single.body().contains("\"name\":\"models/platform/admin-listing-projection\""),
                () -> "Admin GET must project canonical ID for API entries: " + single.body());
    }
}
