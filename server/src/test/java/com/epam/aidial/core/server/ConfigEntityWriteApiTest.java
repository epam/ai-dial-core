package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 3S.2 (write API for {@code interceptors}, {@code roles},
 * {@code keys}, {@code routes}, {@code schemas}) amended by slice U.0 (2026-05-20) to the
 * unified PUT-upsert wire shape. POST is universally 405 with {@code Allow: GET, PUT, DELETE};
 * PUT honors {@code If-None-Match: *} (412 if exists) and {@code If-Match: <etag>} (412 on
 * mismatch). DELETE is unchanged. Exercises the keys-only blank-key + apiKeyStore fast-path and
 * the schemas-only raw-JSON pass-through under the new shape.
 */
public class ConfigEntityWriteApiTest extends ResourceBaseTest {

    private static final String INTERCEPTOR_BODY = """
            {
              "endpoint": "http://localhost:7001/forward"
            }
            """;

    private static final String INTERCEPTOR_BODY_UPDATED = """
            {
              "endpoint": "http://localhost:7001/forward-v2"
            }
            """;

    private static final String ROLE_BODY = """
            {
              "limits": {}
            }
            """;

    private static final String ROLE_BODY_UPDATED = """
            {
              "limits": {"gpt-4": {"minute": "100", "day": "1000"}}
            }
            """;

    private static final String KEY_BODY_PROJECT_A = """
            {
              "key": "secret123",
              "project": "projA",
              "roles": ["admin"]
            }
            """;

    private static final String KEY_BODY_NO_KEY = """
            {
              "project": "projA",
              "roles": ["admin"]
            }
            """;

    private static final String KEY_BODY_PROJECT_B_NO_KEY = """
            {
              "project": "projB",
              "roles": ["admin"]
            }
            """;

    private static final String ROUTE_BODY = """
            {
              "paths": ["/foo"],
              "methods": ["GET"],
              "upstreams": [{"endpoint": "http://localhost:7001"}],
              "response": {"status": 200, "body": "ok"}
            }
            """;

    private static final String ROUTE_BODY_UPDATED = """
            {
              "paths": ["/foo"],
              "methods": ["GET"],
              "upstreams": [{"endpoint": "http://localhost:7001"}],
              "response": {"status": 201, "body": "created"}
            }
            """;

    private static final String SCHEMA_BODY = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {"name": {"type": "string"}}
            }
            """;

    private static final String SCHEMA_BODY_UPDATED = """
            {
              "$schema": "https://json-schema.org/draft/2020-12/schema",
              "type": "object",
              "properties": {"name": {"type": "string"}, "age": {"type": "integer"}}
            }
            """;

    // ---- interceptors ------------------------------------------------------

    @Test
    void testInterceptorPutCreate200HappyPath() {
        Response put = send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-create",
                null, INTERCEPTOR_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
        assertTrue(put.body().contains("\"name\":\"test-interceptor-create\""),
                () -> "Expected name in body: " + put.body());
    }

    @Test
    void testInterceptorPutIfNoneMatchStar412OnExisting() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-conflict", null,
                INTERCEPTOR_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-conflict", null,
                INTERCEPTOR_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testInterceptorPut200HappyPathUpdate() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-update", null,
                INTERCEPTOR_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-update", null,
                INTERCEPTOR_BODY_UPDATED, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));

        Response get = send(HttpMethod.GET, "/v1/interceptors/platform/test-interceptor-update", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("forward-v2"), () -> "Expected updated endpoint: " + get.body());
    }

    @Test
    void testInterceptorPutBareUpsertCreatesOnMissing() {
        // Bare PUT against missing — upsert creates (was 404 pre-U.0).
        Response put = send(HttpMethod.PUT, "/v1/interceptors/platform/no-such-interceptor", null,
                INTERCEPTOR_BODY, "authorization", "admin");
        verify(put, 200);
    }

    @Test
    void testPutEmptyBodyReturns400() {
        // FINDING #5: an empty request body is rejected before coercion — no silent "{}" upsert.
        Response put = send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-empty", null,
                "", "authorization", "admin");
        verify(put, 400);
    }

    @Test
    void testPutEmptyBodyDoesNotOverwrite() {
        // FINDING #5: a rejected empty-body PUT must leave the existing entity untouched.
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-keep", null,
                INTERCEPTOR_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-keep", null,
                "", "authorization", "admin"), 400);
        Response get = send(HttpMethod.GET, "/v1/interceptors/platform/test-interceptor-keep", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("http://localhost:7001/forward"),
                () -> "Original endpoint must be unchanged: " + get.body());
    }

    @Test
    void testInterceptorDelete204HappyPath() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-delete", null,
                INTERCEPTOR_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/interceptors/platform/test-interceptor-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/interceptors/platform/test-interceptor-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testInterceptorDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/interceptors/platform/no-such-interceptor-del", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- roles -------------------------------------------------------------

    @Test
    void testRolePutCreate200HappyPath() {
        Response put = send(HttpMethod.PUT, "/v1/roles/platform/test-role-create",
                null, ROLE_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
        assertTrue(put.body().contains("\"name\":\"test-role-create\""),
                () -> "Expected name in body: " + put.body());
    }

    @Test
    void testRolePutIfNoneMatchStar412OnExisting() {
        verify(send(HttpMethod.PUT, "/v1/roles/platform/test-role-conflict", null,
                ROLE_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/roles/platform/test-role-conflict", null,
                ROLE_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testRolePut200HappyPathUpdate() {
        verify(send(HttpMethod.PUT, "/v1/roles/platform/test-role-update", null,
                ROLE_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/roles/platform/test-role-update", null,
                ROLE_BODY_UPDATED, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
    }

    @Test
    void testRolePutBareUpsertCreatesOnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/roles/platform/no-such-role-create", null,
                ROLE_BODY, "authorization", "admin");
        verify(put, 200);
    }

    @Test
    void testRoleDelete204HappyPath() {
        verify(send(HttpMethod.PUT, "/v1/roles/platform/test-role-delete", null,
                ROLE_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/roles/platform/test-role-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/roles/platform/test-role-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testRoleDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/roles/platform/no-such-role-del", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- keys --------------------------------------------------------------

    @Test
    void testKeyPutCreate200HappyPath() {
        Response put = send(HttpMethod.PUT, "/v1/keys/platform/test-key-create",
                null, KEY_BODY_PROJECT_A, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
        assertTrue(put.body().contains("\"name\":\"test-key-create\""),
                () -> "Expected name in body: " + put.body());
    }

    @Test
    void testKeyPutCreateApiKeyAuthenticatesAfterCreate() {
        // End-to-end check that the freshly-created key is registered under its plaintext secret
        // (not the encrypted blob form). A GET that requires Api-key auth must succeed under the
        // newly issued secret; if apiKeyStore is keyed by ciphertext, the request 401s.
        String body = """
                {
                  "key": "secret-auth-roundtrip",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/keys/platform/test-key-auth", null,
                body, "authorization", "admin", "If-None-Match", "*"), 200);

        Response bucket = send(HttpMethod.GET, "/v1/bucket", null, "",
                "Api-key", "secret-auth-roundtrip");
        verify(bucket, 200);
    }

    @Test
    void rotatingKeyViaPutRemovesOldSecretFromAuthStore() {
        // FINDING #2: rotating a key's secret via PUT must revoke the old auth bearer.
        String bodyOld = """
                {
                  "key": "secret-rotate-old",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        String bodyNew = """
                {
                  "key": "secret-rotate-new",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/keys/platform/test-key-rotate", null,
                bodyOld, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.GET, "/v1/bucket", null, "",
                "Api-key", "secret-rotate-old"), 200);

        verify(send(HttpMethod.PUT, "/v1/keys/platform/test-key-rotate", null,
                bodyNew, "authorization", "admin"), 200);

        // Old secret revoked, new secret authenticates.
        verify(send(HttpMethod.GET, "/v1/bucket", null, "",
                "Api-key", "secret-rotate-old"), 401);
        verify(send(HttpMethod.GET, "/v1/bucket", null, "",
                "Api-key", "secret-rotate-new"), 200);
    }

    @Test
    void testKeyPut400OnBlankKey() {
        Response put = send(HttpMethod.PUT, "/v1/keys/platform/test-key-blank",
                null, KEY_BODY_NO_KEY, "authorization", "admin", "If-None-Match", "*");
        verify(put, 400);
        assertTrue(put.body().toLowerCase().contains("key"),
                () -> "Expected key-related error: " + put.body());
    }

    @Test
    void testKeyPutIfNoneMatchStar412OnExisting() {
        // Use unique secret per test to avoid leaking into the apiKeyStore from other tests.
        String body = """
                {
                  "key": "secret-conflict",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/keys/platform/test-key-conflict", null,
                body, "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/keys/platform/test-key-conflict", null,
                body, "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testKeyPut200HappyPathUpdate() {
        String body = """
                {
                  "key": "secret-put-update",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        String bodyUpdated = """
                {
                  "key": "secret-put-update",
                  "project": "projB",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/keys/platform/test-key-update", null,
                body, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/keys/platform/test-key-update", null,
                bodyUpdated, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
    }

    @Test
    void testKeyPut200PreserveKeyOnOmit() {
        String body = """
                {
                  "key": "secret-preserve",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/keys/platform/test-key-preserve", null,
                body, "authorization", "admin", "If-None-Match", "*"), 200);

        // PUT body omits "key": a 200 response proves preserve-on-omit pulled the encrypted secret
        // from the existing blob — otherwise the post-merge blank-key check would 400. The secret
        // value itself is not asserted because U.4 retired the plaintext-reveal path.
        Response put = send(HttpMethod.PUT, "/v1/keys/platform/test-key-preserve", null,
                KEY_BODY_PROJECT_B_NO_KEY, "authorization", "admin");
        verify(put, 200);
    }

    @Test
    void testKeyPutBareUpsertCreatesOnMissing() {
        // Bare PUT against missing — upsert creates (was 404 pre-U.0).
        Response put = send(HttpMethod.PUT, "/v1/keys/platform/no-such-key-create", null,
                KEY_BODY_PROJECT_A, "authorization", "admin");
        verify(put, 200);
    }

    @Test
    void testKeyDelete204HappyPath() {
        String body = """
                {
                  "key": "secret-delete",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/keys/platform/test-key-delete", null,
                body, "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/keys/platform/test-key-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/keys/platform/test-key-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testKeyDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/keys/platform/no-such-key-del", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- routes ------------------------------------------------------------

    @Test
    void testRoutePutCreate200HappyPath() {
        Response put = send(HttpMethod.PUT, "/v1/routes/platform/test-route-create",
                null, ROUTE_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
        assertTrue(put.body().contains("\"name\":\"test-route-create\""),
                () -> "Expected name in body: " + put.body());
    }

    @Test
    void testRoutePutIfNoneMatchStar412OnExisting() {
        verify(send(HttpMethod.PUT, "/v1/routes/platform/test-route-conflict", null,
                ROUTE_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/routes/platform/test-route-conflict", null,
                ROUTE_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testRoutePut200HappyPathUpdate() {
        verify(send(HttpMethod.PUT, "/v1/routes/platform/test-route-update", null,
                ROUTE_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/routes/platform/test-route-update", null,
                ROUTE_BODY_UPDATED, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
    }

    @Test
    void testRoutePutBareUpsertCreatesOnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/routes/platform/no-such-route-create", null,
                ROUTE_BODY, "authorization", "admin");
        verify(put, 200);
    }

    @Test
    void testRouteDelete204HappyPath() {
        verify(send(HttpMethod.PUT, "/v1/routes/platform/test-route-delete", null,
                ROUTE_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/routes/platform/test-route-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/routes/platform/test-route-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testRouteDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/routes/platform/no-such-route-del", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- schemas -----------------------------------------------------------

    @Test
    void testSchemaPutCreate200HappyPath() {
        Response put = send(HttpMethod.PUT, "/v1/schemas/platform/test-schema-create",
                null, SCHEMA_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
        assertTrue(put.body().contains("\"name\":\"test-schema-create\""),
                () -> "Expected name in body: " + put.body());

        Response get = send(HttpMethod.GET, "/v1/schemas/platform/test-schema-create", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("json-schema.org"),
                () -> "Expected schema URL in projected body: " + get.body());
    }

    @Test
    void testSchemaPutIfNoneMatchStar412OnExisting() {
        verify(send(HttpMethod.PUT, "/v1/schemas/platform/test-schema-conflict", null,
                SCHEMA_BODY, "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/schemas/platform/test-schema-conflict", null,
                SCHEMA_BODY, "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testSchemaPut200HappyPathUpdate() {
        verify(send(HttpMethod.PUT, "/v1/schemas/platform/test-schema-update", null,
                SCHEMA_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/schemas/platform/test-schema-update", null,
                SCHEMA_BODY_UPDATED, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));

        Response get = send(HttpMethod.GET, "/v1/schemas/platform/test-schema-update", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"age\""), () -> "Expected updated schema property: " + get.body());
    }

    @Test
    void testSchemaPutBareUpsertCreatesOnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/schemas/platform/no-such-schema-create", null,
                SCHEMA_BODY, "authorization", "admin");
        verify(put, 200);
    }

    @Test
    void testSchemaDelete204HappyPath() {
        verify(send(HttpMethod.PUT, "/v1/schemas/platform/test-schema-delete", null,
                SCHEMA_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        Response del = send(HttpMethod.DELETE, "/v1/schemas/platform/test-schema-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/schemas/platform/test-schema-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testSchemaDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/schemas/platform/no-such-schema-del", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- cross-cutting -----------------------------------------------------

    @Test
    void testWriteMetadataListShowsImmediately() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-immediate", null,
                INTERCEPTOR_BODY, "authorization", "admin", "If-None-Match", "*"), 200);

        // U.0: listings moved to the /v1/metadata/... sibling route and use ResourceFolderMetadata shape.
        Response list = send(HttpMethod.GET, "/v1/metadata/interceptors/platform/", null, "",
                "authorization", "admin");
        verify(list, 200);
        assertTrue(list.body().contains("test-interceptor-immediate"),
                () -> "Expected listing to include the new entity: " + list.body());
    }

    @Test
    void testPut403ForNonAdmin() {
        Response put = send(HttpMethod.PUT, "/v1/interceptors/platform/test-interceptor-noadmin", null,
                INTERCEPTOR_BODY, "authorization", "user", "If-None-Match", "*");
        verify(put, 403);
    }
}
