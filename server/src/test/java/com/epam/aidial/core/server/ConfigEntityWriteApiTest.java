package com.epam.aidial.core.server;

import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 3S.2: write API for the remaining writable entity types
 * exposed by {@code ConfigResourceController} — {@code interceptors}, {@code roles},
 * {@code keys}, {@code routes}, and {@code schemas}. Mirrors the strict-split semantics
 * (POST=409, PUT=404, DELETE=404) and rebuildNow() immediacy guarantees established for
 * models in slice 2S.11 / 2S.14, and exercises the keys-only blank-key + apiKeyStore
 * fast-path plus the schemas-only raw-JSON pass-through.
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

    private static final String KEY_BODY_SENTINEL = """
            {
              "key": "***",
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
    void testInterceptorPost201HappyPath() {
        Response post = send(HttpMethod.POST, "/v1/interceptors/platform/test-interceptor-create",
                null, INTERCEPTOR_BODY, "authorization", "admin");
        verify(post, 201);
        assertNotNull(post.headers().get("etag"));
        assertTrue(post.body().contains("\"name\":\"test-interceptor-create\""),
                () -> "Expected name in body: " + post.body());
    }

    @Test
    void testInterceptorPost409OnConflict() {
        verify(send(HttpMethod.POST, "/v1/interceptors/platform/test-interceptor-conflict", null,
                INTERCEPTOR_BODY, "authorization", "admin"), 201);
        Response again = send(HttpMethod.POST, "/v1/interceptors/platform/test-interceptor-conflict", null,
                INTERCEPTOR_BODY, "authorization", "admin");
        verify(again, 409);
    }

    @Test
    void testInterceptorPut200HappyPath() {
        verify(send(HttpMethod.POST, "/v1/interceptors/platform/test-interceptor-update", null,
                INTERCEPTOR_BODY, "authorization", "admin"), 201);

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
    void testInterceptorPut404OnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/interceptors/platform/no-such-interceptor", null,
                INTERCEPTOR_BODY, "authorization", "admin");
        verify(put, 404);
    }

    @Test
    void testInterceptorDelete204HappyPath() {
        verify(send(HttpMethod.POST, "/v1/interceptors/platform/test-interceptor-delete", null,
                INTERCEPTOR_BODY, "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/interceptors/platform/test-interceptor-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/interceptors/platform/test-interceptor-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testInterceptorDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/interceptors/platform/no-such-interceptor", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- roles -------------------------------------------------------------

    @Test
    void testRolePost201HappyPath() {
        Response post = send(HttpMethod.POST, "/v1/roles/platform/test-role-create",
                null, ROLE_BODY, "authorization", "admin");
        verify(post, 201);
        assertNotNull(post.headers().get("etag"));
        assertTrue(post.body().contains("\"name\":\"test-role-create\""),
                () -> "Expected name in body: " + post.body());
    }

    @Test
    void testRolePost409OnConflict() {
        verify(send(HttpMethod.POST, "/v1/roles/platform/test-role-conflict", null,
                ROLE_BODY, "authorization", "admin"), 201);
        Response again = send(HttpMethod.POST, "/v1/roles/platform/test-role-conflict", null,
                ROLE_BODY, "authorization", "admin");
        verify(again, 409);
    }

    @Test
    void testRolePut200HappyPath() {
        verify(send(HttpMethod.POST, "/v1/roles/platform/test-role-update", null,
                ROLE_BODY, "authorization", "admin"), 201);

        Response put = send(HttpMethod.PUT, "/v1/roles/platform/test-role-update", null,
                ROLE_BODY_UPDATED, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
    }

    @Test
    void testRolePut404OnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/roles/platform/no-such-role", null,
                ROLE_BODY, "authorization", "admin");
        verify(put, 404);
    }

    @Test
    void testRoleDelete204HappyPath() {
        verify(send(HttpMethod.POST, "/v1/roles/platform/test-role-delete", null,
                ROLE_BODY, "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/roles/platform/test-role-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/roles/platform/test-role-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testRoleDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/roles/platform/no-such-role", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- keys --------------------------------------------------------------

    @Test
    void testKeyPost201HappyPath() {
        Response post = send(HttpMethod.POST, "/v1/keys/platform/test-key-create",
                null, KEY_BODY_PROJECT_A, "authorization", "admin");
        verify(post, 201);
        assertNotNull(post.headers().get("etag"));
        assertTrue(post.body().contains("\"name\":\"test-key-create\""),
                () -> "Expected name in body: " + post.body());
    }

    @Test
    void testKeyPost201ApiKeyAuthenticatesAfterCreate() {
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
        verify(send(HttpMethod.POST, "/v1/keys/platform/test-key-auth", null,
                body, "authorization", "admin"), 201);

        Response bucket = send(HttpMethod.GET, "/v1/bucket", null, "",
                "Api-key", "secret-auth-roundtrip");
        verify(bucket, 200);
    }

    @Test
    void testKeyPost400OnSentinelKey() {
        Response post = send(HttpMethod.POST, "/v1/keys/platform/test-key-sentinel",
                null, KEY_BODY_SENTINEL, "authorization", "admin");
        verify(post, 400);
        assertTrue(post.body().contains("***"), () -> "Expected sentinel mention in error: " + post.body());
    }

    @Test
    void testKeyPost400OnBlankKey() {
        Response post = send(HttpMethod.POST, "/v1/keys/platform/test-key-blank",
                null, KEY_BODY_NO_KEY, "authorization", "admin");
        verify(post, 400);
        assertTrue(post.body().toLowerCase().contains("key"),
                () -> "Expected key-related error: " + post.body());
    }

    @Test
    void testKeyPost409OnConflict() {
        // Use unique secret per test to avoid leaking into the apiKeyStore from other tests.
        String body = """
                {
                  "key": "secret-conflict",
                  "project": "projA",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/keys/platform/test-key-conflict", null,
                body, "authorization", "admin"), 201);
        Response again = send(HttpMethod.POST, "/v1/keys/platform/test-key-conflict", null,
                body, "authorization", "admin");
        verify(again, 409);
    }

    @Test
    void testKeyPut200HappyPath() {
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
        verify(send(HttpMethod.POST, "/v1/keys/platform/test-key-update", null,
                body, "authorization", "admin"), 201);

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
        verify(send(HttpMethod.POST, "/v1/keys/platform/test-key-preserve", null,
                body, "authorization", "admin"), 201);

        // PUT body omits "key": a 200 response proves preserve-on-omit pulled the encrypted secret
        // from the existing blob — otherwise the post-merge blank-key check would 400. A reveal-
        // secrets GET would need a dual admin+security-admin fixture (admin to read platform/, plus
        // security-admin to unmask), which ResourceBaseTest.createClaims doesn't currently produce.
        Response put = send(HttpMethod.PUT, "/v1/keys/platform/test-key-preserve", null,
                KEY_BODY_PROJECT_B_NO_KEY, "authorization", "admin");
        verify(put, 200);
    }

    @Test
    void testKeyPut404OnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/keys/platform/no-such-key", null,
                KEY_BODY_PROJECT_A, "authorization", "admin");
        verify(put, 404);
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
        verify(send(HttpMethod.POST, "/v1/keys/platform/test-key-delete", null,
                body, "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/keys/platform/test-key-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/keys/platform/test-key-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testKeyDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/keys/platform/no-such-key", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- routes ------------------------------------------------------------

    @Test
    void testRoutePost201HappyPath() {
        Response post = send(HttpMethod.POST, "/v1/routes/platform/test-route-create",
                null, ROUTE_BODY, "authorization", "admin");
        verify(post, 201);
        assertNotNull(post.headers().get("etag"));
        assertTrue(post.body().contains("\"name\":\"test-route-create\""),
                () -> "Expected name in body: " + post.body());
    }

    @Test
    void testRoutePost409OnConflict() {
        verify(send(HttpMethod.POST, "/v1/routes/platform/test-route-conflict", null,
                ROUTE_BODY, "authorization", "admin"), 201);
        Response again = send(HttpMethod.POST, "/v1/routes/platform/test-route-conflict", null,
                ROUTE_BODY, "authorization", "admin");
        verify(again, 409);
    }

    @Test
    void testRoutePut200HappyPath() {
        verify(send(HttpMethod.POST, "/v1/routes/platform/test-route-update", null,
                ROUTE_BODY, "authorization", "admin"), 201);

        Response put = send(HttpMethod.PUT, "/v1/routes/platform/test-route-update", null,
                ROUTE_BODY_UPDATED, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));
    }

    @Test
    void testRoutePut404OnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/routes/platform/no-such-route", null,
                ROUTE_BODY, "authorization", "admin");
        verify(put, 404);
    }

    @Test
    void testRouteDelete204HappyPath() {
        verify(send(HttpMethod.POST, "/v1/routes/platform/test-route-delete", null,
                ROUTE_BODY, "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/routes/platform/test-route-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/routes/platform/test-route-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testRouteDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/routes/platform/no-such-route", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- schemas -----------------------------------------------------------

    @Test
    void testSchemaPost201HappyPath() {
        Response post = send(HttpMethod.POST, "/v1/schemas/public/test-schema-create",
                null, SCHEMA_BODY, "authorization", "admin");
        verify(post, 201);
        assertNotNull(post.headers().get("etag"));
        assertTrue(post.body().contains("\"name\":\"test-schema-create\""),
                () -> "Expected name in body: " + post.body());

        Response get = send(HttpMethod.GET, "/v1/schemas/public/test-schema-create", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("json-schema.org"),
                () -> "Expected schema URL in projected body: " + get.body());
    }

    @Test
    void testSchemaPost409OnConflict() {
        verify(send(HttpMethod.POST, "/v1/schemas/public/test-schema-conflict", null,
                SCHEMA_BODY, "authorization", "admin"), 201);
        Response again = send(HttpMethod.POST, "/v1/schemas/public/test-schema-conflict", null,
                SCHEMA_BODY, "authorization", "admin");
        verify(again, 409);
    }

    @Test
    void testSchemaPut200HappyPath() {
        verify(send(HttpMethod.POST, "/v1/schemas/public/test-schema-update", null,
                SCHEMA_BODY, "authorization", "admin"), 201);

        Response put = send(HttpMethod.PUT, "/v1/schemas/public/test-schema-update", null,
                SCHEMA_BODY_UPDATED, "authorization", "admin");
        verify(put, 200);
        assertNotNull(put.headers().get("etag"));

        Response get = send(HttpMethod.GET, "/v1/schemas/public/test-schema-update", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"age\""), () -> "Expected updated schema property: " + get.body());
    }

    @Test
    void testSchemaPut404OnMissing() {
        Response put = send(HttpMethod.PUT, "/v1/schemas/public/no-such-schema", null,
                SCHEMA_BODY, "authorization", "admin");
        verify(put, 404);
    }

    @Test
    void testSchemaDelete204HappyPath() {
        verify(send(HttpMethod.POST, "/v1/schemas/public/test-schema-delete", null,
                SCHEMA_BODY, "authorization", "admin"), 201);

        Response del = send(HttpMethod.DELETE, "/v1/schemas/public/test-schema-delete", null, "",
                "authorization", "admin");
        verify(del, 204);

        Response get = send(HttpMethod.GET, "/v1/schemas/public/test-schema-delete", null, "",
                "authorization", "admin");
        verify(get, 404);
    }

    @Test
    void testSchemaDelete404OnMissing() {
        Response del = send(HttpMethod.DELETE, "/v1/schemas/public/no-such-schema", null, "",
                "authorization", "admin");
        verify(del, 404);
    }

    // ---- cross-cutting -----------------------------------------------------

    @Test
    void testWriteGetListShowsImmediately() {
        verify(send(HttpMethod.POST, "/v1/interceptors/platform/test-interceptor-immediate", null,
                INTERCEPTOR_BODY, "authorization", "admin"), 201);

        // No polling — rebuildNow() in the writer ensures the listing reflects the new entity.
        Response list = send(HttpMethod.GET, "/v1/interceptors/platform", null, "",
                "authorization", "admin");
        verify(list, 200);
        assertTrue(list.body().contains("test-interceptor-immediate"),
                () -> "Expected listing to include the new entity: " + list.body());
    }

    @Test
    void testPost403ForNonAdmin() {
        Response post = send(HttpMethod.POST, "/v1/interceptors/platform/test-interceptor-noadmin", null,
                INTERCEPTOR_BODY, "authorization", "user");
        verify(post, 403);
    }
}
