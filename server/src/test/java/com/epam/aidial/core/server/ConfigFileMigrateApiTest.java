package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ConfigFileMigrateApiTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
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
    void testMigrateSchemasDisambiguatesSameDisplayName() {
        // Three fixture schemas share the same dial:applicationTypeDisplayName ("Specific
        // Application Type") but have distinct $id values; the hash suffix must keep their minted
        // names distinct.
        String body = """
                {"types": ["schemas"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");

        Set<String> migratedIds = idsWithStatus(results, "migrated");
        assertEquals(4, migratedIds.size(), () -> "Body: " + response.body());

        long specificApplicationTypeCount = migratedIds.stream()
                .filter(migratedId -> migratedId.startsWith("schemas/platform/specific-application-type-"))
                .count();
        // migratedIds is a Set, so 3 distinct entries here already proves the hash suffix
        // disambiguated all three same-display-name schemas.
        assertEquals(3, specificApplicationTypeCount, () -> "Body: " + response.body());

        for (String migratedId : migratedIds) {
            String name = migratedId.substring("schemas/platform/".length());
            verify(send(HttpMethod.GET, "/v1/schemas/platform/" + name, null, "",
                    "authorization", "admin"), 200);
        }
    }

    @Test
    @SneakyThrows
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
    void testTypesFilterOnlyMigratesRequestedTypes() {
        String body = """
                {"types": ["keys"]}
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin");
        verify(response, 200);
        JsonNode results = ProxyUtil.MAPPER.readTree(response.body()).get("results");
        for (JsonNode r : results) {
            assertTrue(r.get("id").asText().startsWith("keys/platform/"),
                    () -> "Unexpected type migrated: " + response.body());
        }
        // Untouched type never migrated.
        verify(send(HttpMethod.GET, "/v1/models/platform/test-model-v1", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testUnsupportedTypeReturns400() {
        String body = """
                {"types": ["not-a-real-type"]}
                """;
        verify(send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, body,
                "authorization", "admin"), 400);
    }

    @Test
    void testNonAdminReturns403() {
        verify(send(HttpMethod.POST, "/v1/admin/config/file/migrate", null, "{}",
                "authorization", "user"), 403);
    }

    private static Set<String> idsWithStatus(JsonNode results, String status) {
        Set<String> ids = new HashSet<>();
        for (JsonNode r : results) {
            if (status.equals(r.get("status").asText())) {
                ids.add(r.get("id").asText());
            }
        }
        return ids;
    }
}
