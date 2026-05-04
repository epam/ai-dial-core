package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for slice 4S.0: {@code POST /v1/admin/apply}. Batch admin write
 * endpoint with manifest list, optional precheck, and per-entity status reporting.
 */
public class AdminApplyApiTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testApplyHappyPathAllKinds() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Interceptor",
                      "name": "apply-int-1",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Model",
                      "name": "apply-model-1",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["interceptors/platform/apply-int-1"]
                      }
                    },
                    {
                      "kind": "Settings",
                      "name": "global",
                      "spec": {"globalInterceptors": [], "retriableErrorCodes": [502, 503]}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(3, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        for (JsonNode r : parsed.get("results")) {
            assertEquals("applied", r.get("status").asText(), () -> "Body: " + response.body());
        }
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/apply-int-1", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/models/public/apply-model-1", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    void testApplyPrecheckRejectsOnDanglingRef() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "apply-precheck-bad",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["does-not-exist"]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode results = parsed.get("results");
        assertEquals(1, results.size());
        assertEquals("skipped", results.get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/public/apply-precheck-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyPrecheckMixedBatchRejectedAtomically() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Interceptor",
                      "name": "apply-mixed-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Model",
                      "name": "apply-mixed-bad",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["does-not-exist"]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt());
        JsonNode results = parsed.get("results");
        assertEquals(2, results.size());
        for (JsonNode r : results) {
            assertEquals("skipped", r.get("status").asText());
        }
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/apply-mixed-int", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/public/apply-mixed-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyPrecheckFalsePartialFailure() {
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "apply-partial-good",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    },
                    {
                      "kind": "Model",
                      "name": "apply-partial-bad",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["does-not-exist"]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt());
        verify(send(HttpMethod.GET, "/v1/models/public/apply-partial-good", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/models/public/apply-partial-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyBundleKindReturns400() {
        String body = """
                {
                  "manifests": [
                    {"kind": "Bundle", "name": "x", "spec": {}}
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin"), 400);
    }

    @Test
    @SneakyThrows
    void testApplyUnknownKindPerEntityFailed() {
        String body = """
                {
                  "manifests": [
                    {"kind": "Whatever", "name": "x", "spec": {}}
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt());
        assertEquals(1, parsed.get("failed").asInt());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText());
    }

    @Test
    @SneakyThrows
    void testApplyNonAdminReturns403() {
        String body = """
                {"manifests": []}
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "user"), 403);
    }

    @Test
    @SneakyThrows
    void testApplyDependencyOrderProof() {
        // Model listed BEFORE interceptor; server resorts so the cross-ref resolves.
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "apply-order-model",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["interceptors/platform/apply-order-int"]
                      }
                    },
                    {
                      "kind": "Interceptor",
                      "name": "apply-order-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(2, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
    }

    @Test
    @SneakyThrows
    void testApplyApplicationAndToolSet() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Application",
                      "name": "apply-app-1",
                      "spec": {
                        "endpoint": "http://example.com/v1/completions",
                        "display_name": "Apply App"
                      }
                    },
                    {
                      "kind": "ToolSet",
                      "name": "apply-toolset-1",
                      "spec": {
                        "transport": "http",
                        "endpoint": "http://localhost:9876",
                        "display_name": "Apply Toolset"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(2, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        verify(send(HttpMethod.GET, "/v1/applications/public/apply-app-1", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/toolsets/public/apply-toolset-1", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    void testApplyEmptyManifestsBatchOk() {
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, "{\"manifests\": []}",
                "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt());
        assertEquals(0, parsed.get("failed").asInt());
    }

    @Test
    @SneakyThrows
    void testApplyMalformedEnvelopeMissingManifests() {
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, "{}", "authorization", "admin"), 400);
    }

    @Test
    @SneakyThrows
    void testApplySettingsSpecialCase() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Settings",
                      "name": "global",
                      "spec": {"globalInterceptors": ["interceptor1"], "retriableErrorCodes": []}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        Response get = send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode settings = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("api", settings.get("source").asText(), () -> "Body: " + get.body());
        JsonNode globalInterceptors = settings.get("globalInterceptors");
        assertNotNull(globalInterceptors);
        assertEquals(1, globalInterceptors.size());
        assertEquals("interceptor1", globalInterceptors.get(0).asText());
    }

    @Test
    @SneakyThrows
    void testApplyKeyUpdatesApiKeyStore() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Key",
                      "name": "apply-key-1",
                      "spec": {
                        "key": "applySecret123",
                        "project": "EPM-RTC-APPLY",
                        "role": "default"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        // The new project key should now authenticate any plain proxy call.
        Response bucketResp = send(HttpMethod.GET, "/v1/bucket", null, "", "api-key", "applySecret123");
        assertTrue(bucketResp.status() == 200,
                () -> "Expected 200 for new key, got " + bucketResp.status() + ": " + bucketResp.body());
    }

    public static class SoftValidation extends ResourceBaseTest {
        @Override
        protected JsonObject additionalSettingsOverrides() {
            return new JsonObject()
                    .put("config", new JsonObject()
                            .put("write", new JsonObject().put("softValidation", true))
                            .put("onInvalidEntity", "skip"));
        }

        @Test
        @SneakyThrows
        void testApplyAdmitsInvalidUnderSoftValidation() {
            String body = """
                    {
                      "precheck": false,
                      "manifests": [
                        {
                          "kind": "Model",
                          "name": "apply-soft-invalid",
                          "spec": {
                            "type": "chat",
                            "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                            "interceptors": ["does-not-exist"]
                          }
                        }
                      ]
                    }
                    """;
            Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
            verify(response, 200);
            JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
            assertEquals("applied_invalid", parsed.get("results").get(0).get("status").asText());

            // Wait for rebuild to surface the invalid record.
            JsonNode found = null;
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                Response get = send(HttpMethod.GET, "/v1/models/public/apply-soft-invalid", null, "",
                        "authorization", "admin");
                if (get.status() == 200) {
                    JsonNode node = ProxyUtil.MAPPER.readTree(get.body());
                    if ("invalid".equals(node.path("status").asText())) {
                        found = node;
                        break;
                    }
                }
                Thread.sleep(100);
            }
            assertNotNull(found, "Expected status=invalid after rebuild");
            JsonNode warnings = found.get("validationWarnings");
            assertNotNull(warnings);
            assertTrue(warnings.isArray() && warnings.size() >= 1);
        }
    }
}
