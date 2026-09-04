package com.epam.aidial.core.server;

import com.epam.aidial.core.server.controller.ConfigFileMigrateController;
import com.epam.aidial.core.server.util.HashUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigFileMigrateApiTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateModelAndInterceptor() {
        String body = """
                {"types": ["models", "interceptors"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");
        assertTrue(idsWithStatus(results, "migrated").contains("models/platform/test-model-v1"),
                () -> "Body: " + response.body());
        assertTrue(idsWithStatus(results, "migrated").contains("interceptors/platform/interceptor1"),
                () -> "Body: " + response.body());

        JsonNode modelResult = findByBlobName(results, "models/platform/test-model-v1");
        assertEquals("Model", modelResult.get("kind").asText(), () -> "Body: " + response.body());
        assertEquals("test-model-v1", modelResult.get("fileId").asText(), () -> "Body: " + response.body());

        JsonNode interceptorResult = findByBlobName(results, "interceptors/platform/interceptor1");
        assertEquals("Interceptor", interceptorResult.get("kind").asText(), () -> "Body: " + response.body());
        assertEquals("interceptor1", interceptorResult.get("fileId").asText(), () -> "Body: " + response.body());

        verify(send(HttpMethod.GET, "/v1/models/platform/test-model-v1", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/interceptor1", null, "",
                "authorization", "admin"), 200);

        Response models = send(HttpMethod.GET, "/openai/models", null, "", "authorization", "admin");
        verify(models, 200);
        JsonNode data = ProxyUtil.MAPPER.readTree(models.body()).get("data");
        int occurrences = 0;
        for (JsonNode m : data) {
            if ("test-model-v1".equals(m.get("id").asText())) {
                occurrences++;
            }
        }
        assertEquals(1, occurrences, () -> "Expected exactly one 'test-model-v1' entry: " + models.body());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateIsIdempotent() {
        String body = """
                {"types": ["models"]}
                """;
        Response first = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(first, 200);
        JsonNode firstResults = ProxyUtil.MAPPER.readTree(first.body()).get("results");
        assertTrue(idsWithStatus(firstResults, "migrated").contains("models/platform/test-model-v1"),
                () -> "Body: " + first.body());

        Response second = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(second, 200);
        JsonNode secondResults = ProxyUtil.MAPPER.readTree(second.body()).get("results");
        for (JsonNode r : secondResults) {
            assertEquals("skipped", r.get("status").asText(), () -> "Body: " + second.body());
        }
        assertTrue(idsWithStatus(secondResults, "skipped").contains("models/platform/test-model-v1"),
                () -> "Body: " + second.body());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testDryRunWritesNothing() {
        String body = """
                {"types": ["roles"], "dryRun": true}
                """;
        Response dryRun = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(dryRun, 200);
        JsonNode dryRunResults = ProxyUtil.MAPPER.readTree(dryRun.body()).get("results");
        assertTrue(idsWithStatus(dryRunResults, "would_migrate").contains("roles/platform/default"),
                () -> "Body: " + dryRun.body());

        // Nothing was actually written.
        verify(send(HttpMethod.GET, "/v1/roles/platform/default", null, "",
                "authorization", "admin"), 404);

        String realBody = """
                {"types": ["roles"], "dryRun": false}
                """;
        Response real = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, realBody,
                "authorization", "admin");
        verify(real, 200);
        JsonNode realResults = ProxyUtil.MAPPER.readTree(real.body()).get("results");
        assertTrue(idsWithStatus(realResults, "migrated").contains("roles/platform/default"),
                () -> "Body: " + real.body());
        verify(send(HttpMethod.GET, "/v1/roles/platform/default", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateToolSetKeepsSecretEncrypted() {
        // The fixture's "oauth-toolset" carries auth_settings.client_secret in plaintext file config.
        // ToolSetService.putToolSet performs OAuth protected-resource-metadata discovery against the
        // toolset's own endpoint (localhost:9876), so a real listener is required here.
        String body = """
                {"types": ["toolsets"]}
                """;
        try (TestWebServer ignore = new TestWebServer(9876)) {
            Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                    "authorization", "admin");
            verify(response, 200);
            JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");
            assertTrue(idsWithStatus(results, "migrated").contains("toolsets/platform/oauth-toolset"),
                    () -> "Body: " + response.body());

            Response get = send(HttpMethod.GET, "/v1/toolsets/platform/oauth-toolset", null, "",
                    "authorization", "admin");
            verify(get, 200);
            assertFalse(get.body().contains("test-client-secret"),
                    () -> "Plaintext client_secret must never appear on GET: " + get.body());
            assertFalse(get.body().contains("\"client_secret\""),
                    () -> "client_secret field must be absent from GET response: " + get.body());
        }
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateSchemasUseLastPathSegmentAsBlobName() {
        // The 4 fixture schemas' $id values have distinct last path segments, so each migrates
        // verbatim under that segment as its blob name — no disambiguation needed.
        String body = """
                {"types": ["schemas"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");

        Set<String> migratedIds = idsWithStatus(results, "migrated");
        Set<String> expectedIds = Set.of(
                "schemas/platform/schema_endpoint",
                "schemas/platform/specific_application_type",
                "schemas/platform/specific_toolset_type",
                "schemas/platform/chained_application_type");
        assertEquals(expectedIds, migratedIds, () -> "Body: " + response.body());

        for (String migratedId : migratedIds) {
            String name = migratedId.substring("schemas/platform/".length());
            verify(send(HttpMethod.GET, "/v1/schemas/platform/" + name, null, "",
                    "authorization", "admin"), 200);
        }

        for (JsonNode r : results) {
            assertEquals("Schema", r.get("kind").asText(), () -> "Body: " + response.body());
            assertTrue(r.get("fileId").asText().startsWith("https://mydial.somewhere.com/custom_application_schemas/"),
                    () -> "Body: " + response.body());
        }
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateSchemasIdempotentReportsMatchedBlobName() {
        // The schema's own $id is never a blob path, so a repeat run must report the *matched
        // existing blob's* name, not the raw $id, once the schema is already migrated.
        String body = """
                {"types": ["schemas"]}
                """;
        verify(send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body, "authorization", "admin"), 200);

        Response second = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(second, 200);
        JsonNode secondResults = ProxyUtil.MAPPER.readTree(second.body()).get("results");

        JsonNode skipped = findByFileId(secondResults, "https://mydial.somewhere.com/custom_application_schemas/schema_endpoint");
        assertEquals("skipped", skipped.get("status").asText(), () -> "Body: " + second.body());
        assertEquals("Schema", skipped.get("kind").asText(), () -> "Body: " + second.body());
        assertEquals("schemas/platform/schema_endpoint", skipped.get("blobName").asText(), () -> "Body: " + second.body());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate-bad-schema.json")
    void testMigrateSchemaWithUnresolvableBlobNameReportsFileIdOnly() {
        // The $id's last path segment ("bad~schema~name") has characters that don't match
        // ConfigResourceController.ENTITY_NAME_PATTERN, so no blob name can ever be derived for it —
        // the result must fall back to the $id as fileId, with no blobName (nothing was, or could be,
        // written). Tildes are valid, unescaped URI characters, so the $id itself still passes the
        // config file's own schema-meta-schema validation at startup.
        String body = """
                {"types": ["schemas"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");
        assertEquals(1, results.size(), () -> "Body: " + response.body());

        JsonNode result = results.get(0);
        assertEquals("failed", result.get("status").asText(), () -> "Body: " + response.body());
        assertEquals("Schema", result.get("kind").asText(), () -> "Body: " + response.body());
        assertEquals("https://mydial.somewhere.com/custom_application_schemas/bad~schema~name",
                result.get("fileId").asText(), () -> "Body: " + response.body());
        assertFalse(result.has("blobName"), () -> "Body: " + response.body());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateSettings() {
        String body = """
                {"types": ["settings"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");
        assertTrue(idsWithStatus(results, "migrated").contains("settings/platform/global"),
                () -> "Body: " + response.body());

        Response get = send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode settings = ProxyUtil.MAPPER.readTree(get.body());
        assertTrue(settings.get("globalInterceptors").isArray());
        assertTrue(settings.get("retriableErrorCodes").isArray());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testTypesFilterOnlyMigratesRequestedTypes() {
        String body = """
                {"types": ["keys"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");
        assertEquals(5, results.size(), () -> "Body: " + response.body());
        for (JsonNode r : results) {
            assertTrue(r.get("blobName").asText().startsWith("keys/platform/"),
                    () -> "Unexpected type migrated: " + response.body());
            assertEquals("migrated", r.get("status").asText(),
                    () -> "Key migration must actually succeed, not just target the right type: " + response.body());
            assertEquals("Key", r.get("kind").asText(), () -> "Body: " + response.body());
            // A key's file-side identity is its raw secret, which must never be echoed back.
            assertFalse(r.has("fileId"), () -> "Key result must never carry a fileId: " + response.body());
        }
        // Untouched type never migrated.
        verify(send(HttpMethod.GET, "/v1/models/platform/test-model-v1", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateKeysPopulatesSecretAndAuthenticates() {
        String body = """
                {"types": ["keys"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");

        Set<String> migratedIds = idsWithStatus(results, "migrated");
        Set<String> expectedIds = Set.of(
                expectedKeyId("proxyKey1", "EPM-RTC-GPT"),
                expectedKeyId("proxyKey2", "EPM-RTC-RAIL"),
                expectedKeyId("proxyKey3", "EPM-RTC-DIAL"),
                expectedKeyId("vstore_user_key", "test"),
                expectedKeyId("vstore_admin_key", "test"));
        assertEquals(expectedIds, migratedIds, () -> "Body: " + response.body());

        for (String secret : FIXTURE_KEY_SECRETS) {
            for (String keyId : migratedIds) {
                assertFalse(keyId.contains(secret), () -> "Migrated id leaks plaintext secret: " + keyId);
            }
        }

        String proxyKey1Id = expectedKeyId("proxyKey1", "EPM-RTC-GPT");
        String proxyKey1Name = proxyKey1Id.substring("keys/platform/".length());
        Response get = send(HttpMethod.GET, "/v1/keys/platform/" + proxyKey1Name, null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode keyEntity = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("EPM-RTC-GPT", keyEntity.get("project").asText());
        assertEquals("default", keyEntity.get("role").asText());

        // The migrated blob's secret must round-trip through encryption and be usable for real auth.
        verify(send(HttpMethod.GET, "/v1/bucket", null, "", "Api-key", "proxyKey1"), 200);
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateKeysSameProjectDifferentSecretsNoCollision() {
        // vstore_user_key and vstore_admin_key share project "test" but have distinct secrets —
        // exactly the fixture pairing that rules out project-only naming.
        String body = """
                {"types": ["keys"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");
        Set<String> migratedIds = idsWithStatus(results, "migrated");

        String userKeyId = expectedKeyId("vstore_user_key", "test");
        String adminKeyId = expectedKeyId("vstore_admin_key", "test");
        assertTrue(migratedIds.contains(userKeyId), () -> "Body: " + response.body());
        assertTrue(migratedIds.contains(adminKeyId), () -> "Body: " + response.body());
        assertNotEquals(userKeyId, adminKeyId);

        verify(send(HttpMethod.GET, "/v1/bucket", null, "", "Api-key", "vstore_user_key"), 200);
        verify(send(HttpMethod.GET, "/v1/bucket", null, "", "Api-key", "vstore_admin_key"), 200);
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateKeysIsIdempotent() {
        String body = """
                {"types": ["keys"]}
                """;
        Response first = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(first, 200);
        JsonNode firstResults = ProxyUtil.MAPPER.readTree(first.body()).get("results");
        assertEquals(5, idsWithStatus(firstResults, "migrated").size(), () -> "Body: " + first.body());

        Response second = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(second, 200);
        JsonNode secondResults = ProxyUtil.MAPPER.readTree(second.body()).get("results");
        assertEquals(5, secondResults.size(), () -> "Body: " + second.body());
        for (JsonNode r : secondResults) {
            assertEquals("skipped", r.get("status").asText(), () -> "Body: " + second.body());
            assertFalse(r.has("fileId"), () -> "Skipped key result must never carry a fileId: " + second.body());
        }
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testMigrateKeysNameCollisionFailsWithoutOverwriting() {
        // Simulate a truncated-hash collision by pre-creating a blob under proxyKey1's exact
        // derived name, backed by an unrelated secret — as if two different secrets had hashed
        // to the same truncated prefix. Migration must refuse to overwrite it.
        String collisionId = expectedKeyId("proxyKey1", "EPM-RTC-GPT");
        String collisionName = collisionId.substring("keys/platform/".length());
        String collidingBody = """
                {
                  "key": "unrelated-existing-secret",
                  "project": "someone-else",
                  "roles": ["admin"]
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/keys/platform/" + collisionName, null,
                collidingBody, "authorization", "admin", "If-None-Match", "*"), 200);

        String body = """
                {"types": ["keys"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");

        JsonNode collisionResult = null;
        for (JsonNode r : results) {
            if (collisionId.equals(r.get("blobName").asText())) {
                collisionResult = r;
            }
        }
        assertNotNull(collisionResult, () -> "Missing result for " + collisionId + ": " + response.body());
        assertEquals("failed", collisionResult.get("status").asText(), () -> "Body: " + response.body());
        assertFalse(collisionResult.has("fileId"), () -> "Failed key result must never carry a fileId: " + response.body());

        // The pre-existing occupant's secret must survive untouched.
        verify(send(HttpMethod.GET, "/v1/bucket", null, "", "Api-key", "unrelated-existing-secret"), 200);

        // The blob itself must still be the original occupant, not overwritten by proxyKey1's data.
        Response get = send(HttpMethod.GET, "/v1/keys/platform/" + collisionName, null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode keyEntity = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("someone-else", keyEntity.get("project").asText(), () -> "Body: " + get.body());

        // The other 4 fixture keys are unaffected by the collision.
        Set<String> migratedIds = idsWithStatus(results, "migrated");
        assertTrue(migratedIds.contains(expectedKeyId("proxyKey2", "EPM-RTC-RAIL")), () -> "Body: " + response.body());
        assertTrue(migratedIds.contains(expectedKeyId("proxyKey3", "EPM-RTC-DIAL")), () -> "Body: " + response.body());
        assertTrue(migratedIds.contains(expectedKeyId("vstore_user_key", "test")), () -> "Body: " + response.body());
        assertTrue(migratedIds.contains(expectedKeyId("vstore_admin_key", "test")), () -> "Body: " + response.body());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testUnsupportedTypeReturns400() {
        String body = """
                {"types": ["not-a-real-type"]}
                """;
        verify(send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin"), 400);
    }

    @Test
    @DialConfigLocation("dial-config/config-file-migrate.json")
    void testNonAdminReturns403() {
        verify(send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, "{}",
                "authorization", "user"), 403);
    }

    // Literal secret strings from the "keys" section of the test fixture (dial-config/config-file-migrate.json).
    private static final String[] FIXTURE_KEY_SECRETS = {
            "proxyKey1", "proxyKey2", "proxyKey3", "vstore_user_key", "vstore_admin_key"
    };

    private static String expectedKeyId(String secret, String project) {
        String hash = HashUtil.sha256Hex(secret).substring(0, ConfigFileMigrateController.KEY_HASH_LENGTH);
        return "keys/platform/" + project.toLowerCase(Locale.ROOT) + "-" + hash;
    }

    private static JsonNode findByBlobName(JsonNode results, String blobName) {
        for (JsonNode r : results) {
            if (r.hasNonNull("blobName") && blobName.equals(r.get("blobName").asText())) {
                return r;
            }
        }
        throw new AssertionError("No result with blobName " + blobName + " in " + results);
    }

    private static JsonNode findByFileId(JsonNode results, String fileId) {
        for (JsonNode r : results) {
            if (r.hasNonNull("fileId") && fileId.equals(r.get("fileId").asText())) {
                return r;
            }
        }
        throw new AssertionError("No result with fileId " + fileId + " in " + results);
    }

    private static Set<String> idsWithStatus(JsonNode results, String status) {
        Set<String> ids = new HashSet<>();
        for (JsonNode r : results) {
            if (status.equals(r.get("status").asText())) {
                ids.add(r.get("blobName").asText());
            }
        }
        return ids;
    }
}
