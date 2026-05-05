package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 2S.15: canonical IDs surface as the {@code id}/{@code model}
 * fields on the legacy {@code /openai/models} and {@code /openai/deployments} listings for
 * API-managed entries; file-sourced entries continue to surface their simple names. Locks the
 * OQ-23 contract that clients can copy a listing's identifier verbatim into chat-completion URLs.
 *
 * <p>The new admin Configuration API listing ({@code /v1/models/public/...}) is unaffected — it
 * projects {@code simpleName(mapKey)} independently per design 03 §4 and is regression-guarded
 * inside {@link ModelWriteApiTest}.
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
        verify(send(HttpMethod.POST, "/v1/models/public/canonical-test", null, API_MODEL_BODY,
                "authorization", "admin"), 201);

        Response list = send(HttpMethod.GET, "/openai/models", null, "");
        verify(list, 200);
        assertTrue(list.body().contains("\"id\":\"models/public/canonical-test\""),
                () -> "Expected canonical id for API-managed model: " + list.body());
        assertTrue(list.body().contains("\"model\":\"models/public/canonical-test\""),
                () -> "Expected canonical model field for API-managed model: " + list.body());
    }

    @Test
    void testApiManagedModelSurfacedAsCanonicalIdInOpenAiDeployments() {
        verify(send(HttpMethod.POST, "/v1/models/public/canonical-deployments", null, API_MODEL_BODY,
                "authorization", "admin"), 201);

        Response list = send(HttpMethod.GET, "/openai/deployments", null, "");
        verify(list, 200);
        assertTrue(list.body().contains("\"id\":\"models/public/canonical-deployments\""),
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
    void testApiManagedModelAdminListingStillProjectsSimpleName() {
        // Regression guard for design 03 §4: the new admin Configuration API listing must continue
        // to project simpleName(mapKey) independently of Model.name. Slice 2S.15 dropped the
        // Model.name reset; the controller's projection layer must keep masking the canonical form.
        verify(send(HttpMethod.POST, "/v1/models/public/admin-listing-projection", null, API_MODEL_BODY,
                "authorization", "admin"), 201);

        Response single = send(HttpMethod.GET, "/v1/models/public/admin-listing-projection", null, "",
                "authorization", "admin");
        verify(single, 200);
        assertTrue(single.body().contains("\"name\":\"admin-listing-projection\""),
                () -> "Admin GET must project simple name: " + single.body());
    }
}
