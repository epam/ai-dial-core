package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 2S.11: write API for {@code /v1/models/public/{name}}.
 * Covers strict-split semantics (POST=409, PUT=404, DELETE=404), {@code If-Match} preconditions,
 * preserve-on-omit secret merging, sentinel rejection on POST, and {@code ?reveal_secrets=true}
 * gated by the security-admin role.
 *
 * <p>Slice 2S.14: write controllers call {@code MergedConfigStore.rebuildNow()} on the writer pod,
 * making post-write GETs immediately consistent — no polling helpers needed.
 */
public class ModelWriteApiTest extends ResourceBaseTest {

    private static final String MODEL_BODY_NO_SECRET = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions"
            }
            """;

    private static final String MODEL_BODY_WITH_SECRET = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "upstreams": [
                {"endpoint": "http://localhost:7001", "key": "real-secret"}
              ]
            }
            """;

    private static final String MODEL_BODY_WITH_SENTINEL_KEY = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "upstreams": [
                {"endpoint": "http://localhost:7001", "key": "***"}
              ]
            }
            """;

    private static final String MODEL_BODY_OMIT_KEY = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "upstreams": [
                {"endpoint": "http://localhost:7001"}
              ]
            }
            """;

    private static final String MODEL_BODY_TRIPLE_STAR = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "upstreams": [
                {"endpoint": "http://localhost:7001", "key": "***"}
              ]
            }
            """;

    @Test
    void testPost201HappyPath() {
        Response post = send(HttpMethod.POST, "/v1/models/public/test-model-create",
                null, MODEL_BODY_NO_SECRET, "authorization", "admin");
        verify(post, 201);
        assertNotNull(post.headers().get("etag"));
        assertTrue(post.body().contains("\"name\":\"test-model-create\""),
                () -> "Expected name in body: " + post.body());

        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-create", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"source\":\"api\""), () -> "Expected source=api: " + get.body());
        assertTrue(get.body().contains("\"status\":\"valid\""), () -> "Expected status=valid: " + get.body());
        assertTrue(get.body().contains("\"name\":\"test-model-create\""),
                () -> "Expected name in body: " + get.body());
    }

    @Test
    void testPost409OnConflict() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-conflict", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin"), 201);
        Response again = send(HttpMethod.POST, "/v1/models/public/test-model-conflict", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin");
        verify(again, 409);
    }

    @Test
    void testPost400OnSentinelInUpstreamKey() {
        Response post = send(HttpMethod.POST, "/v1/models/public/test-model-sentinel", null,
                MODEL_BODY_WITH_SENTINEL_KEY, "authorization", "admin");
        verify(post, 400);
        assertTrue(post.body().contains("***"), () -> "Expected sentinel mention in error: " + post.body());
    }

    @Test
    void testPost403ForNonAdmin() {
        Response post = send(HttpMethod.POST, "/v1/models/public/test-model-noadmin", null,
                MODEL_BODY_NO_SECRET, "authorization", "user");
        verify(post, 403);
    }

    @Test
    void testPut200HappyPath() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-update", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin"), 201);

        String updatedBody = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "displayName": "Updated"
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-update", null, updatedBody,
                "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));

        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-update", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"displayName\":\"Updated\""),
                () -> "Expected displayName=Updated: " + get.body());
    }

    @Test
    void testPut404OnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/models/public/no-such-model", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin");
        verify(put, 404);
    }

    @Test
    void testPut412OnStaleIfMatch() {
        Response post = send(HttpMethod.POST, "/v1/models/public/test-model-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin");
        verify(post, 201);

        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-Match", "\"stale\"");
        verify(put, 412);
    }

    @Test
    void testPutPreservesOmittedSecret() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-omit", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin"), 201);

        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-omit", null, MODEL_BODY_OMIT_KEY,
                "authorization", "admin");
        verify(put, 200);

        // Default GET: secret masked as "***".
        Response masked = send(HttpMethod.GET, "/v1/models/public/test-model-omit", null, "",
                "authorization", "admin");
        verify(masked, 200);
        assertTrue(masked.body().contains("\"key\":\"***\""),
                () -> "Expected masked key after PUT-omit: " + masked.body());

        // Reveal as security-admin: original plaintext is preserved.
        Response revealed = send(HttpMethod.GET, "/v1/models/public/test-model-omit",
                "reveal_secrets=true", "", "authorization", "security-admin");
        verify(revealed, 200);
        assertTrue(revealed.body().contains("\"key\":\"real-secret\""),
                () -> "Expected real-secret in revealed body: " + revealed.body());
    }

    @Test
    void testPutTreatsTripleStarAsPreserve() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-star", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin"), 201);

        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-star", null, MODEL_BODY_TRIPLE_STAR,
                "authorization", "admin");
        verify(put, 200);

        Response revealed = send(HttpMethod.GET, "/v1/models/public/test-model-star",
                "reveal_secrets=true", "", "authorization", "security-admin");
        verify(revealed, 200);
        assertTrue(revealed.body().contains("\"key\":\"real-secret\""),
                () -> "Expected original secret intact after PUT with ***: " + revealed.body());
    }

    @Test
    void testDelete204HappyPath() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-delete", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/models/public/test-model-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/models/public/no-such-model", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    @Test
    void testDelete412OnStaleIfMatch() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-delete-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/models/public/test-model-delete-etag", null, "",
                "authorization", "admin", "If-Match", "\"stale\"");
        verify(del, 412);
    }

    @Test
    void testGetDefaultMasksSecrets() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-mask", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin"), 201);

        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-mask", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"key\":\"***\""),
                () -> "Expected masked key in default GET: " + get.body());
        assertFalse(get.body().contains("real-secret"),
                () -> "Plaintext secret must not appear in default GET: " + get.body());
    }

    @Test
    void testGetRevealSecretsAsSecurityAdmin() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-reveal", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin"), 201);

        Response revealed = send(HttpMethod.GET, "/v1/models/public/test-model-reveal",
                "reveal_secrets=true", "", "authorization", "security-admin");
        verify(revealed, 200);
        assertTrue(revealed.body().contains("\"key\":\"real-secret\""),
                () -> "Expected plaintext secret for security-admin reveal: " + revealed.body());
    }

    @Test
    void testGetRevealSecretsAsPlainAdmin() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-reveal-deny", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin"), 201);

        Response forbidden = send(HttpMethod.GET, "/v1/models/public/test-model-reveal-deny",
                "reveal_secrets=true", "", "authorization", "admin");
        verify(forbidden, 403);
    }

    @Test
    void testPost405ForNonModelType() {
        // Slice 2S.11 supports writes only on "models"; POST against any other writable type
        // must respond 405 with the eventual Allow set per prepareModelWrite/respondWriteMethodNotAllowed.
        Response post = send(HttpMethod.POST, "/v1/roles/platform/test-role", null,
                "{\"limits\":{}}", "authorization", "admin");
        verify(post, 405);
        assertEquals("GET, POST, PUT, DELETE", post.headers().get("Allow"));
    }

    @Test
    void testPostImmediatelyVisibleOnGet() {
        Response post = send(HttpMethod.POST, "/v1/models/public/test-model-immediate-post", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin");
        verify(post, 201);

        // No polling — rebuildNow() in the writer makes the new entity visible by the time the
        // POST response returns. Asserts the immediacy guarantee from slice 2S.14.
        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-immediate-post", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"test-model-immediate-post\""),
                () -> "Expected immediate visibility of POST: " + get.body());
    }

    @Test
    void testDeleteImmediatelyVisibleOnGet() {
        verify(send(HttpMethod.POST, "/v1/models/public/test-model-immediate-delete", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/models/public/test-model-immediate-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        // No polling — rebuildNow() ensures the DELETE removes the entity from the merged Config
        // before the response returns; the very next GET must 404.
        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-immediate-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }
}
