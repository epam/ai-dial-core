package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for slice 4S.1: {@code POST /v1/admin/validate}. Multi-entity,
 * batch-aware validate with {@code precheck} semantics. Predicts apply outcome — same
 * envelope as {@code /v1/admin/apply}, response shape mirrors apply minus the
 * {@code applied} count. Validation never persists; every test that probes for
 * persistence asserts a 404 (or non-{@code "api"} source for the Settings singleton).
 */
public class AdminValidateApiTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testV01HappyPathSingleModel() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "validate-happy-model",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        assertEquals("valid", parsed.get("results").get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/public/validate-happy-model", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV02HappyPathAllKinds() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Settings",
                      "name": "global",
                      "spec": {"globalInterceptors": [], "retriableErrorCodes": []}
                    },
                    {
                      "kind": "Schema",
                      "name": "validate-schema",
                      "spec": {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object"}
                    },
                    {
                      "kind": "Interceptor",
                      "name": "validate-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Role",
                      "name": "validate-role",
                      "spec": {"limits": {}}
                    },
                    {
                      "kind": "Key",
                      "name": "validate-key",
                      "spec": {"key": "validateSecret123", "project": "EPM-RTC-VALIDATE", "role": "default"}
                    },
                    {
                      "kind": "Route",
                      "name": "validate-route",
                      "spec": {"paths": ["/route/.*"], "methods": ["GET"], "response": {"status": 200, "body": "ok"}}
                    },
                    {
                      "kind": "Model",
                      "name": "validate-model",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    },
                    {
                      "kind": "ToolSet",
                      "name": "validate-toolset",
                      "spec": {
                        "transport": "http",
                        "endpoint": "http://localhost:9876",
                        "display_name": "Validate Toolset"
                      }
                    },
                    {
                      "kind": "Application",
                      "name": "validate-app",
                      "spec": {"endpoint": "http://example.com/v1/completions", "display_name": "Validate App"}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(9, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        for (JsonNode r : parsed.get("results")) {
            assertEquals("valid", r.get("status").asText(), () -> "Body: " + response.body());
        }
        // None of these were written.
        verify(send(HttpMethod.GET, "/v1/schemas/public/validate-schema", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/validate-int", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/roles/platform/validate-role", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/keys/platform/validate-key", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/routes/platform/validate-route", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/public/validate-model", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/toolsets/public/validate-toolset", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/applications/public/validate-app", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV03PrecheckTrueDanglingRefReturns422() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "validate-precheck-bad",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["does-not-exist"]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt());
        assertEquals(1, parsed.get("failed").asInt());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/public/validate-precheck-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV04PrecheckTrueMixedBatch422Atomic() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Interceptor",
                      "name": "validate-mixed-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Model",
                      "name": "validate-mixed-bad",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["does-not-exist"]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt());
        assertEquals(1, parsed.get("failed").asInt());
        JsonNode results = parsed.get("results");
        assertEquals(2, results.size());
        // Interceptor passed validate but the batch was rejected → status "skipped".
        // The Model failed → status "FAILED".
        boolean sawSkipped = false;
        boolean sawFailed = false;
        for (JsonNode r : results) {
            if ("skipped".equals(r.get("status").asText())) {
                sawSkipped = true;
            } else if ("FAILED".equals(r.get("status").asText())) {
                sawFailed = true;
            }
        }
        assertTrue(sawSkipped, () -> "Expected one 'skipped' entry: " + response.body());
        assertTrue(sawFailed, () -> "Expected one 'FAILED' entry: " + response.body());
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/validate-mixed-int", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/public/validate-mixed-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV05PrecheckFalseDanglingRefReturns200WithFailed() {
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "validate-soft-bad",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["does-not-exist"]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt());
        assertEquals(1, parsed.get("failed").asInt());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/public/validate-soft-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV06PrecheckFalsePartialResults() {
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "validate-partial-good",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    },
                    {
                      "kind": "Model",
                      "name": "validate-partial-bad",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["does-not-exist"]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt());
        verify(send(HttpMethod.GET, "/v1/models/public/validate-partial-good", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/public/validate-partial-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV07SentinelInModelSpec() {
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "validate-sentinel-model",
                      "spec": {
                        "type": "chat",
                        "upstreams": [
                          {"endpoint": "http://localhost:7001", "key": "***"}
                        ]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/public/validate-sentinel-model", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV08SentinelInKeySpec() {
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "Key",
                      "name": "validate-sentinel-key",
                      "spec": {"key": "***", "project": "EPM-RTC-VALIDATE", "role": "default"}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText());
    }

    @Test
    @SneakyThrows
    void testV09BundleKindReturns400() {
        String body = """
                {
                  "manifests": [{"kind": "Bundle", "name": "x", "spec": {}}]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin"), 400);
    }

    @Test
    @SneakyThrows
    void testV10UnknownKindPerEntityFailed() {
        // Validate diverges from apply on unknown kinds: apply lets them pass precheck and FAIL
        // at the apply step, but validate reports FAILED so the CLI's validate-first gate stops
        // the batch before any apply call.
        String body = """
                {
                  "precheck": false,
                  "manifests": [{"kind": "Whatever", "name": "x", "spec": {}}]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText());
    }

    @Test
    @SneakyThrows
    void testV11DependencyOrderWithinBatchProof() {
        // Model listed BEFORE the interceptor it references; server resorts so the cross-ref
        // resolves. Same proof as apply's testApplyDependencyOrderProof.
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "validate-order-model",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["interceptors/platform/validate-order-int"]
                      }
                    },
                    {
                      "kind": "Interceptor",
                      "name": "validate-order-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(2, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        verify(send(HttpMethod.GET, "/v1/models/public/validate-order-model", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/validate-order-int", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV12EmptyManifestsBatchOk() {
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, "{\"manifests\": []}",
                "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt());
        assertEquals(0, parsed.get("failed").asInt());
        assertEquals(0, parsed.get("results").size());
    }

    @Test
    @SneakyThrows
    void testV13MissingManifestsField() {
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, "{}", "authorization", "admin"), 400);
    }

    @Test
    @SneakyThrows
    void testV14NonAdminReturns403() {
        verify(send(HttpMethod.POST, "/v1/admin/validate", null, "{\"manifests\": []}",
                "authorization", "user"), 403);
    }

    @Test
    @SneakyThrows
    void testV15InvalidJsonStructurePerEntityFailed() {
        // limits is expected as an object on the Deployment entity; passing a string forces a
        // Jackson deserialization failure surfaced as per-entity FAILED.
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "validate-jackson-bad",
                      "spec": {"type": "chat", "limits": "not-an-object"}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/public/validate-jackson-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV16SettingsDoesNotPersist() {
        // Settings has a file/default GET projection that always returns 200; distinguish "not
        // persisted" by checking the source field is NOT "api". Ship a sentinel value in
        // retriableErrorCodes that the file/default does not have, and confirm absence post-call.
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Settings",
                      "name": "global",
                      "spec": {"globalInterceptors": [], "retriableErrorCodes": [599]}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("valid").asInt(), () -> "Body: " + response.body());

        Response get = send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode settings = ProxyUtil.MAPPER.readTree(get.body());
        assertNotEquals("api", settings.get("source").asText(),
                () -> "Settings should not be sourced from API after validate: " + get.body());
        // Sentinel 599 must not appear in projected retriableErrorCodes.
        JsonNode codes = settings.get("retriableErrorCodes");
        if (codes != null && codes.isArray()) {
            for (JsonNode code : codes) {
                assertNotEquals(599, code.asInt(),
                        () -> "Validate must not mutate Settings: " + get.body());
            }
        }
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
        void testV17SoftValidationModePerEntityValid() {
            // Under softValidation=true, validateOnly admits dangling cross-refs as "valid"
            // (they would be persisted with status="invalid" by apply, not rejected).
            // Validate mirrors apply's per-entity admission decision.
            String body = """
                    {
                      "precheck": false,
                      "manifests": [
                        {
                          "kind": "Model",
                          "name": "validate-soft-mode",
                          "spec": {
                            "type": "chat",
                            "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                            "interceptors": ["does-not-exist"]
                          }
                        }
                      ]
                    }
                    """;
            Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body,
                    "authorization", "admin");
            verify(response, 200);
            JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals(1, parsed.get("valid").asInt(), () -> "Body: " + response.body());
            assertEquals(0, parsed.get("failed").asInt());
            assertEquals("valid", parsed.get("results").get(0).get("status").asText());
            verify(send(HttpMethod.GET, "/v1/models/public/validate-soft-mode", null, "",
                    "authorization", "admin"), 404);
        }
    }
}
