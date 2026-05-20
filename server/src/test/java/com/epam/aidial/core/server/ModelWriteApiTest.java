package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 2S.11 (write API for {@code /v1/models/public/{name}})
 * amended by slice U.0 (2026-05-20) — PUT-upsert wire shape. POST is universally 405 with
 * {@code Allow: GET, PUT, DELETE}. PUT honors {@code If-None-Match: *} (412 if entity exists)
 * and {@code If-Match: <etag>} (412 on mismatch). Covers preserve-on-omit secret merging,
 * sentinel rejection on PUT, and {@code ?reveal_secrets=true} gated by the security-admin role.
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
    void testPutCreate200HappyPath() {
        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-create",
                null, MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
        assertTrue(put.body().contains("\"name\":\"test-model-create\""),
                () -> "Expected name in body: " + put.body());

        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-create", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"source\":\"api\""), () -> "Expected source=api: " + get.body());
        assertTrue(get.body().contains("\"status\":\"valid\""), () -> "Expected status=valid: " + get.body());
        assertTrue(get.body().contains("\"name\":\"models/public/test-model-create\""),
                () -> "Expected canonical name in body: " + get.body());
    }

    @Test
    void testPost405OnEntityUrl() {
        Response post = send(HttpMethod.POST, "/v1/models/public/test-model-post-405",
                null, MODEL_BODY_NO_SECRET, "authorization", "admin");
        verify(post, 405);
        assertNotNull(post.headers().get("Allow"));
        assertTrue(post.headers().get("Allow").contains("GET")
                        && post.headers().get("Allow").contains("PUT")
                        && post.headers().get("Allow").contains("DELETE"),
                () -> "Expected Allow header to include GET, PUT, DELETE: " + post.headers().get("Allow"));
    }

    @Test
    void testPutIfNoneMatchStar412OnExisting() {
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-inm", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/models/public/test-model-inm", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testPutBareUpsertCreates() {
        // No conditional header → upsert creates when no prior entity exists.
        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-bare", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin");
        verify(put, 200);

        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-bare", null, "",
                "authorization", "admin");
        verify(get, 200);
    }

    @Test
    void testPut400OnSentinelInUpstreamKey() {
        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-sentinel", null,
                MODEL_BODY_WITH_SENTINEL_KEY, "authorization", "admin", "If-None-Match", "*");
        verify(put, 400);
        assertTrue(put.body().contains("***"), () -> "Expected sentinel mention in error: " + put.body());
    }

    @Test
    void testPut403ForNonAdmin() {
        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-noadmin", null,
                MODEL_BODY_NO_SECRET, "authorization", "user");
        verify(put, 403);
    }

    @Test
    void testPut200HappyPathUpdate() {
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-update", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

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
    void testPut412OnStaleIfMatch() {
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-Match", "\"stale\"");
        verify(put, 412);
    }

    @Test
    void testPutPreservesOmittedSecret() {
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-omit", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

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
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-star", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

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
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-delete", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

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
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-delete-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/models/public/test-model-delete-etag", null, "",
                "authorization", "admin", "If-Match", "\"stale\"");
        verify(del, 412);
    }

    @Test
    void testGetDefaultMasksSecrets() {
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-mask", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

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
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-reveal", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response revealed = send(HttpMethod.GET, "/v1/models/public/test-model-reveal",
                "reveal_secrets=true", "", "authorization", "security-admin");
        verify(revealed, 200);
        assertTrue(revealed.body().contains("\"key\":\"real-secret\""),
                () -> "Expected plaintext secret for security-admin reveal: " + revealed.body());
    }

    @Test
    void testGetRevealSecretsAsPlainAdmin() {
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-reveal-deny", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response forbidden = send(HttpMethod.GET, "/v1/models/public/test-model-reveal-deny",
                "reveal_secrets=true", "", "authorization", "admin");
        verify(forbidden, 403);
    }

    @Test
    void testPutImmediatelyVisibleOnGet() {
        Response put = send(HttpMethod.PUT, "/v1/models/public/test-model-immediate-post", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);

        // No polling — rebuildNow() in the writer makes the new entity visible by the time the
        // PUT response returns. Asserts the immediacy guarantee from slice 2S.14.
        Response get = send(HttpMethod.GET, "/v1/models/public/test-model-immediate-post", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"models/public/test-model-immediate-post\""),
                () -> "Expected immediate visibility of PUT (canonical name): " + get.body());
    }

    @Test
    void testPutGetWithUrlEncodedName() {
        // The {name} segment may carry percent-encoded characters; the controller must decode at
        // the route boundary so the stored entity name and canonical id are the decoded form.
        // Repro from PR #1529 / thread r3249802671 — name with spaces and assorted ASCII punctuation.
        String decoded = "new model super !@#$%^&*()_+1234567890-=[]\\|,.<>~`;:'__1.0.0";
        // Percent-encoded for use in the request line — matches what a sane HTTP client would send.
        String encoded = "new%20model%20super%20%21%40%23%24%25%5E%26%2A%28%29_%2B1234567890-%3D%5B%5D%5C%7C%2C.%3C%3E~%60%3B%3A%27__1.0.0";

        verify(send(HttpMethod.PUT, "/v1/models/public/" + encoded, null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response get = send(HttpMethod.GET, "/v1/models/public/" + encoded, null, "",
                "authorization", "admin");
        verify(get, 200);
        // Backslashes and quotes in the decoded name appear escaped in the JSON body.
        String jsonEscaped = decoded.replace("\\", "\\\\").replace("\"", "\\\"");
        assertTrue(get.body().contains("\"name\":\"models/public/" + jsonEscaped + "\""),
                () -> "Expected decoded canonical name in body: " + get.body());
    }

    @Test
    void testDeleteImmediatelyVisibleOnGet() {
        verify(send(HttpMethod.PUT, "/v1/models/public/test-model-immediate-delete", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*"), 200);

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
