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
                      "spec": {
                        "$schema": "https://json-schema.org/draft/2020-12/schema",
                        "$id": "https://dial.epam.com/schemas/validate-schema",
                        "type": "object"
                      }
                    },
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-model",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
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
        assertEquals(10, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        for (JsonNode r : parsed.get("results")) {
            assertEquals("VALID", r.get("status").asText(), () -> "Body: " + response.body());
        }
        // None of these were written.
        verify(send(HttpMethod.GET, "/v1/schemas/platform/validate-schema", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/validate-catalog-schema", null, "",
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
    void testV15bCatalogSchemaNonObjectSpecFails() {
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-bad",
                      "spec": "not-an-object"
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt());
        JsonNode result = parsed.get("results").get(0);
        assertEquals("FAILED", result.get("status").asText());
        assertEquals("CatalogSchema spec must be a JSON object", result.get("error").asText());
    }

    @Test
    @SneakyThrows
    void testV15cCatalogSchemaDuplicateIdWithinBatchRejected() {
        // Precheck-side counterpart of AdminApplyApiTest#testApplyCatalogSchemaDuplicateIdWithinBatchRejected:
        // validateOnly must catch the $id collision, not just applySchema.
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-dup-a",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    },
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-dup-b",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("SKIPPED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(1).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(1).get("error").asText().contains("validate-dup-id"),
                () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/validate-catalog-schema-dup-a", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/validate-catalog-schema-dup-b", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV15dCatalogSchemaDuplicateIdAgainstExistingApplyRejected() {
        // The collision target is committed via /v1/admin/apply, then validate at a different
        // path with the same $id must fail — apply-parity across the two endpoints.
        String existingBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-existing",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-existing-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, existingBody, "authorization", "admin"), 200);

        String conflictBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-conflict",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-existing-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, conflictBody, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(0).get("error").asText().contains("validate-existing-id"),
                () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/validate-catalog-schema-conflict", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV15eCatalogSchemaBlankIdFails() {
        // Closes the second gap: validateOnly never checked for a missing $id at all before this
        // fix, so this used to validate as "valid" and only fail at real-apply time.
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-no-id",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        JsonNode result = parsed.get("results").get(0);
        assertEquals("FAILED", result.get("status").asText());
        assertEquals("CatalogSchema spec must contain a non-blank $id field", result.get("error").asText());
    }

    @Test
    @SneakyThrows
    void testV15fCatalogSchemaDuplicateIdPrecheckFalsePartialResults() {
        // precheck=false: the check still runs per-entity, but the batch is not atomically
        // rejected — the colliding entry stays FAILED, the other one that already validated
        // stays VALID (no skip-collapse), and the overall response is still 200.
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-pf-a",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-pf-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    },
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-pf-b",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-pf-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("VALID", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(1).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(1).get("error").asText().contains("validate-pf-dup-id"),
                () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/validate-catalog-schema-pf-a", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/validate-catalog-schema-pf-b", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testV15gCatalogSchemaIdChangePrecheckRejected() {
        // Precheck-side counterpart of AdminApplyApiTest#testApplyCatalogSchemaIdChangeRejected:
        // the resource is committed via apply first, then validate at the same path with a
        // different $id must report the conflict without applying it.
        String existingBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-id-change",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-id-change-original",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, existingBody, "authorization", "admin"), 200);

        String changedBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/validate-catalog-schema-id-change",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/validate-id-change-different",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/validate", null, changedBody, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("valid").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(0).get("error").asText().contains("cannot be changed"),
                () -> "Body: " + response.body());

        Response get = send(HttpMethod.GET, "/v1/catalog_schemas/platform/validate-catalog-schema-id-change", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("validate-id-change-original"),
                () -> "Expected original $id to be unaffected by a rejected precheck: " + get.body());
    }

    @Test
    @SneakyThrows
    void testValidateAndApplyCatalogSchemaDuplicateIdParity() {
        // Apply-parity: the same colliding-$id batch must 422 identically on both surfaces.
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/parity-catalog-schema-dup-a",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/parity-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    },
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/parity-catalog-schema-dup-b",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/parity-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response validate = send(HttpMethod.POST, "/v1/admin/validate", null, body, "authorization", "admin");
        Response apply = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(validate, 422);
        verify(apply, 422);
        JsonNode validateParsed = ProxyUtil.MAPPER.readTree(validate.body());
        JsonNode applyParsed = ProxyUtil.MAPPER.readTree(apply.body());
        assertEquals("SKIPPED", validateParsed.get("results").get(0).get("status").asText());
        assertEquals("SKIPPED", applyParsed.get("results").get(0).get("status").asText());
        assertEquals("FAILED", validateParsed.get("results").get(1).get("status").asText());
        assertEquals("FAILED", applyParsed.get("results").get(1).get("status").asText());
        assertEquals(validateParsed.get("results").get(1).get("error").asText(),
                applyParsed.get("results").get(1).get("error").asText());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/parity-catalog-schema-dup-a", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/parity-catalog-schema-dup-b", null, "",
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

    @Test
    @SneakyThrows
    void testV18RejectsCacheRateWithoutTokenUnit() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "models/platform/validate-bad-cache-pricing",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "pricing": {
                          "unit": "char_without_whitespace",
                          "prompt": "0.1",
                          "completion": "0.5",
                          "cacheWrite": "0.02"
                        }
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
        verify(send(HttpMethod.GET, "/v1/models/platform/validate-bad-cache-pricing", null, "",
                "authorization", "admin"), 404);
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

        @Test
        @SneakyThrows
        void testV19SoftValidationAdmitsCacheRateWithoutTokenUnit() {
            // Same admission logic as testV17SoftValidationModePerEntityValid: softValidation
            // downgrades the pricing violation to "valid" at validate-time too.
            String body = """
                    {
                      "precheck": false,
                      "manifests": [
                        {
                          "kind": "Model",
                          "name": "models/platform/validate-soft-cache-pricing",
                          "spec": {
                            "type": "chat",
                            "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                            "pricing": {
                              "unit": "char_without_whitespace",
                              "prompt": "0.1",
                              "completion": "0.5",
                              "cacheRead": "0.01"
                            }
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
            verify(send(HttpMethod.GET, "/v1/models/platform/validate-soft-cache-pricing", null, "",
                    "authorization", "admin"), 404);
        }
    }
}
