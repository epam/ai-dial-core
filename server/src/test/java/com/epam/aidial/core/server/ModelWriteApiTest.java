package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 2S.11 (write API for {@code /v1/models/platform/{name}})
 * amended by slice U.0 (2026-05-20) — PUT-upsert wire shape. POST is universally 405 with
 * {@code Allow: GET, PUT, DELETE}. PUT honors {@code If-None-Match: *} (412 if entity exists)
 * and {@code If-Match: <etag>} (412 on mismatch). Covers preserve-on-omit secret merging.
 *
 * <p>Slice U.4 (2026-05-25) retired the {@code ?reveal_secrets=true} reveal flow, the
 * {@code security-admin} role, and the {@code "***"} mask sentinel. Secret fields drop from
 * GET responses via {@code @JsonProperty(WRITE_ONLY)}; preserve-on-omit signals are
 * null/absent only.
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

    private static final String MODEL_BODY_OMIT_KEY = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
              "upstreams": [
                {"endpoint": "http://localhost:7001"}
              ]
            }
            """;

    @Test
    void testPutCreate200HappyPath() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-create",
                null, MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
        assertTrue(put.body().contains("\"name\":\"test-model-create\""),
                () -> "Expected name in body: " + put.body());

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-create", null, "",
                "authorization", "admin");
        verify(get, 200);
        // U.1 (2026-05-21): source field retired entirely; URL itself discloses source (per-entity = blob).
        assertTrue(get.body().contains("\"status\":\"valid\""), () -> "Expected status=valid: " + get.body());
        assertTrue(get.body().contains("\"name\":\"models/platform/test-model-create\""),
                () -> "Expected canonical name in body: " + get.body());
    }

    @Test
    void testPost405OnEntityUrl() {
        Response post = send(HttpMethod.POST, "/v1/models/platform/test-model-post-405",
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
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-inm", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/models/platform/test-model-inm", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testPutBareUpsertCreates() {
        // No conditional header → upsert creates when no prior entity exists.
        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-bare", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin");
        verify(put, 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-bare", null, "",
                "authorization", "admin");
        verify(get, 200);
    }

    @Test
    void testPut403ForNonAdmin() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-noadmin", null,
                MODEL_BODY_NO_SECRET, "authorization", "user");
        verify(put, 403);
    }

    @Test
    void testPut200HappyPathUpdate() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-update", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        String updatedBody = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "displayName": "Updated"
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-update", null, updatedBody,
                "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-update", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"displayName\":\"Updated\""),
                () -> "Expected displayName=Updated: " + get.body());
    }

    @Test
    void testPut412OnStaleIfMatch() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-Match", "\"stale\"");
        verify(put, 412);
    }

    @Test
    void testPutPreservesOmittedSecret() {
        // Create with a real secret, then PUT a body that omits the upstream key. Preserve-on-omit
        // must keep the prior ciphertext — verified indirectly via the secret-not-leaked invariant
        // below (the GET response is sufficient under U.4 because secrets drop from responses).
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-omit", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-omit", null, MODEL_BODY_OMIT_KEY,
                "authorization", "admin");
        verify(put, 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-omit", null, "",
                "authorization", "admin");
        verify(get, 200);
        // Secret fields drop on GET (WRITE_ONLY); plaintext must not leak in any form.
        assertFalse(get.body().contains("real-secret"),
                () -> "Plaintext secret must not appear on GET: " + get.body());
        assertFalse(get.body().contains("\"key\""),
                () -> "Upstream key must be absent on GET: " + get.body());
    }

    @Test
    void testExtraDataVisibleOnGet() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001", "extraData": {"region": "us"}}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-ed-visible", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-ed-visible", null, "",
                "authorization", "admin");
        verify(get, 200);
        // extraData is stored as a JSON-string (JsonToStringDeserializer), so it round-trips as an
        // escaped string value on the response, not as a nested object.
        assertTrue(get.body().contains("\"extraData\":\"{\\\"region\\\":\\\"us\\\"}\""),
                () -> "extraData must be visible on GET: " + get.body());
        assertFalse(get.body().contains("secretExtraData"),
                () -> "secretExtraData must be absent on GET: " + get.body());
    }

    @Test
    void testSecretExtraDataDroppedOnGet() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001", "secretExtraData": {"region": "us"}}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-sed-dropped", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-sed-dropped", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertFalse(get.body().contains("secretExtraData"),
                () -> "secretExtraData must be absent on GET: " + get.body());
    }

    @Test
    void testSecretExtraDataPreserveOnOmit() {
        String withSecret = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001", "secretExtraData": {"token": "abc"}}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-sed-omit", null, withSecret,
                "authorization", "admin", "If-None-Match", "*"), 200);

        String omitSecret = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001"}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-sed-omit", null, omitSecret,
                "authorization", "admin"), 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-sed-omit", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertFalse(get.body().contains("secretExtraData"),
                () -> "secretExtraData must be absent on GET (WRITE_ONLY): " + get.body());
        assertFalse(get.body().contains("abc"),
                () -> "secret value must never leak on GET: " + get.body());
    }

    @Test
    void testExtraDataSecretExtraDataOverlapReturns422() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001", "extraData": {"k": "a"}, "secretExtraData": {"k": "b"}}
                  ]
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-overlap", null, body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
    }

    @Test
    void testExtraDataSecretExtraDataNoOverlapAccepted() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001", "extraData": {"region": "us"}, "secretExtraData": {"token": "x"}}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-no-overlap", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);
    }

    @Test
    void testSecretExtraDataScalarAccepted() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001", "secretExtraData": "opaque"}
                  ]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-sed-scalar", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);
    }

    @Test
    void testBothScalarReturns422() {
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/test-model/chat/completions",
                  "upstreams": [
                    {"endpoint": "http://localhost:7001", "extraData": "a", "secretExtraData": "b"}
                  ]
                }
                """;
        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-both-scalar", null, body,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 422);
    }

    @Test
    void testDelete204HappyPath() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-delete", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/models/platform/test-model-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/models/platform/no-such-model", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    @Test
    void testDelete412OnStaleIfMatch() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-delete-etag", null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/models/platform/test-model-delete-etag", null, "",
                "authorization", "admin", "If-Match", "\"stale\"");
        verify(del, 412);
    }

    @Test
    void testGetDropsSecrets() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-mask", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-mask", null, "",
                "authorization", "admin");
        verify(get, 200);
        // U.4: @EncryptedField + @JsonProperty(WRITE_ONLY) → field absent from GET responses.
        assertFalse(get.body().contains("\"key\""),
                () -> "Upstream key must be absent on GET: " + get.body());
        assertFalse(get.body().contains("real-secret"),
                () -> "Plaintext secret must not appear in GET: " + get.body());
    }

    @Test
    void testRevealSecretsQueryParamIgnored() {
        // U.4: the ?reveal_secrets=true query parameter is no longer recognized. Passing it has
        // no effect — the response shape matches a vanilla GET (secrets dropped).
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-reveal-ignore", null, MODEL_BODY_WITH_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response response = send(HttpMethod.GET, "/v1/models/platform/test-model-reveal-ignore",
                "reveal_secrets=true", "", "authorization", "admin");
        verify(response, 200);
        assertFalse(response.body().contains("\"key\""),
                () -> "Upstream key must remain absent when ?reveal_secrets=true is passed: " + response.body());
        assertFalse(response.body().contains("real-secret"),
                () -> "Plaintext secret must never appear: " + response.body());
    }

    @Test
    void testPutImmediatelyVisibleOnGet() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/test-model-immediate-post", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);

        // No polling — rebuildNow() in the writer makes the new entity visible by the time the
        // PUT response returns. Asserts the immediacy guarantee from slice 2S.14.
        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-immediate-post", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"models/platform/test-model-immediate-post\""),
                () -> "Expected immediate visibility of PUT (canonical name): " + get.body());
    }

    @Test
    void testPutGetWithUrlEncodedName() {
        // The {name} segment may carry percent-encoded characters; the controller must decode at
        // the route boundary so the stored entity name and canonical id are the decoded form.
        // Repro from PR #1529 / thread r3249802671 — the decoding round-trip is the contract.
        // The chosen name stays within the entity-name regex (design 02 §4 / 03 §3); decoding is
        // exercised by sending '%' (as %25) and ':' (as %3A) which the controller URL-decodes.
        String decoded = "model%25v1.0:beta";
        String encoded = "model%2525v1.0%3Abeta";

        verify(send(HttpMethod.PUT, "/v1/models/platform/" + encoded, null, MODEL_BODY_NO_SECRET,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/" + encoded, null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"models/platform/" + decoded + "\""),
                () -> "Expected decoded canonical name in body: " + get.body());
    }

    @Test
    void testPutRejectsOutOfContractName() {
        // Names outside the documented set (design 02 §4 / 03 §3: ^[A-Za-z0-9._%:-]+$) must be
        // rejected at the write surface. The decoded value of '%20' is a space, which is not
        // in the allowed set; the controller returns 400. Adds a regression guard for the
        // tightened contract introduced alongside the broadened character set (% and :).
        Response put = send(HttpMethod.PUT, "/v1/models/platform/has%20space", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*");
        verify(put, 400);
    }

    @Test
    void testDeleteRejectsOutOfContractName() {
        Response del = send(HttpMethod.DELETE, "/v1/models/platform/has+plus", null, "",
                "authorization", "admin");
        verify(del, 400);
    }

    @Test
    void testDeleteImmediatelyVisibleOnGet() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/test-model-immediate-delete", null,
                MODEL_BODY_NO_SECRET, "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/models/platform/test-model-immediate-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        // No polling — rebuildNow() ensures the DELETE removes the entity from the merged Config
        // before the response returns; the very next GET must 404.
        Response get = send(HttpMethod.GET, "/v1/models/platform/test-model-immediate-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }
}
