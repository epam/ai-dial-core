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
                      "name": "models/platform/validate-happy-model",
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
        assertEquals("VALID", parsed.get("results").get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-happy-model", null, "",
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
                      "name": "settings/platform/global",
                      "spec": {"globalInterceptors": [], "retriableErrorCodes": []}
                    },
                    {
                      "kind": "Schema",
                      "name": "schemas/platform/validate-schema",
                      "spec": {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object"}
                    },
                    {
                      "kind": "Interceptor",
                      "name": "interceptors/platform/validate-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Role",
                      "name": "roles/platform/validate-role",
                      "spec": {"limits": {}}
                    },
                    {
                      "kind": "Key",
                      "name": "keys/platform/validate-key",
                      "spec": {"key": "validateSecret123", "project": "EPM-RTC-VALIDATE", "role": "default"}
                    },
                    {
                      "kind": "Route",
                      "name": "routes/platform/validate-route",
                      "spec": {"paths": ["/route/.*"], "methods": ["GET"], "response": {"status": 200, "body": "ok"}}
                    },
                    {
                      "kind": "Model",
                      "name": "models/platform/validate-model",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    },
                    {
                      "kind": "ToolSet",
                      "name": "toolsets/public/validate-toolset",
                      "spec": {
                        "transport": "http",
                        "endpoint": "http://localhost:9876",
                        "display_name": "Validate Toolset"
                      }
                    },
                    {
                      "kind": "Application",
                      "name": "applications/public/validate-app",
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
            assertEquals("VALID", r.get("status").asText(), () -> "Body: " + response.body());
        }
        // None of these were written.
        verify(send(HttpMethod.GET, "/v1/schemas/platform/validate-schema", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/validate-int", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/roles/platform/validate-role", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/keys/platform/validate-key", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/routes/platform/validate-route", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-model", null, "",
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
                      "name": "models/platform/validate-precheck-bad",
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
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-precheck-bad", null, "",
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
                      "name": "interceptors/platform/validate-mixed-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Model",
                      "name": "models/platform/validate-mixed-bad",
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
            if ("SKIPPED".equals(r.get("status").asText())) {
                sawSkipped = true;
            } else if ("FAILED".equals(r.get("status").asText())) {
                sawFailed = true;
            }
        }
        assertTrue(sawSkipped, () -> "Expected one 'skipped' entry: " + response.body());
        assertTrue(sawFailed, () -> "Expected one 'FAILED' entry: " + response.body());
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/validate-mixed-int", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-mixed-bad", null, "",
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
                      "name": "models/platform/validate-soft-bad",
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
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-soft-bad", null, "",
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
                      "name": "models/platform/validate-partial-good",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    },
                    {
                      "kind": "Model",
                      "name": "models/platform/validate-partial-bad",
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
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-partial-good", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-partial-bad", null, "",
                "authorization", "admin"), 404);
    }

    // Slice U.4 (2026-05-25) retired the "***" mask sentinel and its rejection-on-validate. A
    // literal "***" in a spec body is now treated as a real value (re-encrypted on write). The
    // previous V07/V08 tests asserted the rejection contract and have been removed.

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
        // Validate and apply agree on unknown kinds: both report a per-entity FAILED. With
        // precheck=false the failure surfaces inside the 200 batch; with precheck=true (default)
        // it fails precheck and the batch is rejected with 422 (see the parity test below).
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
    void testValidateAndApplyUnknownKindParity() {
        // Under precheck=true (default) an unknown kind fails precheck on both surfaces: 422 with
        // the same results[0] status/error.
        String body = """
                {
                  "manifests": [{"kind": "Whatever", "name": "x", "spec": {}}]
                }
                """;
        Response validate = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        Response apply = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(validate, 422);
        verify(apply, 422);
        JsonNode validateParsed = ProxyUtil.MAPPER.readTree(validate.body());
        JsonNode applyParsed = ProxyUtil.MAPPER.readTree(apply.body());
        assertEquals("FAILED", validateParsed.get("results").get(0).get("status").asText());
        assertEquals("FAILED", applyParsed.get("results").get(0).get("status").asText());
        assertEquals(validateParsed.get("results").get(0).get("error").asText(),
                applyParsed.get("results").get(0).get("error").asText());
        assertEquals("Unknown kind: Whatever", applyParsed.get("results").get(0).get("error").asText());
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
                      "name": "models/platform/validate-order-model",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["interceptors/platform/validate-order-int"]
                      }
                    },
                    {
                      "kind": "Interceptor",
                      "name": "interceptors/platform/validate-order-int",
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
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-order-model", null, "",
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
                      "name": "models/platform/validate-jackson-bad",
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
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-jackson-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV16SettingsDoesNotPersist() {
        // U.1 (2026-05-21): /v1/settings/platform/global is blob-only. After validate (no mutation),
        // there is no blob → GET returns 404. We additionally verify the sentinel does not appear
        // via the file-config GET, which projects file/default values.
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Settings",
                      "name": "settings/platform/global",
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
        System.out.println(get.status());
        System.out.println(get.body());
        verify(get, 404);

        // File-config view: the sentinel must not be present in file/default-sourced values.
        Response fileGet = send(HttpMethod.GET, "/v1/admin/config/file/settings/global", null, "",
                "authorization", "admin");
        verify(fileGet, 200);
        JsonNode settings = ProxyUtil.MAPPER.readTree(fileGet.body());
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
                          "name": "models/platform/validate-soft-mode",
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
            assertEquals("VALID", parsed.get("results").get(0).get("status").asText());
            verify(send(HttpMethod.GET, "/v1/models/platform/validate-soft-mode", null, "",
                    "authorization", "admin"), 404);
        }
    }
}
