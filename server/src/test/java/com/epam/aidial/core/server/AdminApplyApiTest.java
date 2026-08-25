package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                      "name": "interceptors/platform/apply-int-1",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Model",
                      "name": "models/platform/apply-model-1",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["apply-int-1"]
                      }
                    },
                    {
                      "kind": "Settings",
                      "name": "settings/platform/global",
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
            assertEquals("APPLIED", r.get("status").asText(), () -> "Body: " + response.body());
        }
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/apply-int-1", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-model-1", null, "",
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
                      "name": "models/platform/apply-precheck-bad",
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
        // Mirrors /v1/admin/validate: the offending entry stays FAILED on a precheck rejection.
        assertEquals("FAILED", results.get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-precheck-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyRejectsCacheRateWithoutTokenUnit() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "models/platform/apply-bad-cache-pricing",
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
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode results = parsed.get("results");
        assertEquals(1, results.size());
        assertEquals("FAILED", results.get(0).get("status").asText());
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-bad-cache-pricing", null, "",
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
                      "name": "interceptors/platform/apply-mixed-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    },
                    {
                      "kind": "Model",
                      "name": "models/platform/apply-mixed-bad",
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
        // Mirrors /v1/admin/validate: the offending entry stays FAILED; valid siblings collapse to
        // "skipped". Exactly one of each, regardless of dependency-sort order.
        int failed = 0;
        int skipped = 0;
        for (JsonNode r : results) {
            String status = r.get("status").asText();
            if ("FAILED".equals(status)) {
                failed++;
            } else if ("SKIPPED".equals(status)) {
                skipped++;
            }
        }
        assertEquals(1, failed, () -> "Body: " + response.body());
        assertEquals(1, skipped, () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/interceptors/platform/apply-mixed-int", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-mixed-bad", null, "",
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
                      "name": "models/platform/apply-partial-good",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    },
                    {
                      "kind": "Model",
                      "name": "models/platform/apply-partial-bad",
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
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-partial-good", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-partial-bad", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyPrecheckFalseRejectsExtraDataOverlap() {
        // precheck=false must still reject an upstream whose extraData/secretExtraData share a
        // top-level key — overlap is a hard 422, never silently merged (no silent precedence).
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "Model",
                      "name": "models/platform/apply-overlap-bad",
                      "spec": {
                        "type": "chat",
                        "upstreams": [
                          {
                            "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                            "extraData": {"region": "us"},
                            "secretExtraData": {"region": "secret"}
                          }
                        ]
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-overlap-bad", null, "",
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
    void testApplyUnknownKindPrecheckTrueReturns422() {
        // precheck defaults to true: an unknown kind fails precheck and the whole batch is
        // rejected (422) — the valid sibling must NOT be applied.
        String body = """
                {
                  "manifests": [
                    {"kind": "Whatever", "name": "x", "spec": {}},
                    {
                      "kind": "Model",
                      "name": "models/platform/apply-unknown-sibling",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        // The server dependency-sorts the batch, so locate the unknown-kind result by error rather
        // than by index. It must be FAILED with the canonical message; the sibling is "skipped".
        JsonNode unknownResult = null;
        for (JsonNode r : parsed.get("results")) {
            if (r.has("error") && r.get("error").asText().startsWith("Unknown kind:")) {
                unknownResult = r;
            }
        }
        assertNotNull(unknownResult, () -> "Body: " + response.body());
        assertEquals("FAILED", unknownResult.get("status").asText());
        assertEquals("Unknown kind: Whatever", unknownResult.get("error").asText());
        // Nothing applied — the valid sibling must not exist.
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-unknown-sibling", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyUnknownKindPrecheckFalsePerEntityFailed() {
        // precheck=false: the unknown kind is a per-entity FAILED inside a 200 batch; the valid
        // sibling is still applied.
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {"kind": "Whatever", "name": "x", "spec": {}},
                    {
                      "kind": "Model",
                      "name": "models/platform/apply-unknown-pf-sibling",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions"
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
        verify(send(HttpMethod.GET, "/v1/models/platform/apply-unknown-pf-sibling", null, "",
                "authorization", "admin"), 200);
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
                      "name": "models/platform/apply-order-model",
                      "spec": {
                        "type": "chat",
                        "endpoint": "http://localhost:7001/openai/deployments/test/chat/completions",
                        "interceptors": ["apply-order-int"]
                      }
                    },
                    {
                      "kind": "Interceptor",
                      "name": "interceptors/platform/apply-order-int",
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
    void testApplyCatalogSchema() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-1",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-model",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-1", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    void testApplyCatalogSchemaSurvivesUnrelatedApply() {
        // Regression for MergedConfigStore#shallowClone: partial-update writes (applyBatch et al.)
        // clone the merged Config off a fresh `new Config()`. Any Config map not explicitly carried
        // over in shallowClone reverts to its field-initializer default on the very next unrelated
        // admin write, silently wiping previously-applied entries of that type.
        String catalogSchemaBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-survive",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-survive-model",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, catalogSchemaBody, "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-survive", null, "",
                "authorization", "admin"), 200);

        String unrelatedBody = """
                {
                  "manifests": [
                    {
                      "kind": "Interceptor",
                      "name": "interceptors/platform/apply-catalog-unrelated-int",
                      "spec": {"endpoint": "http://localhost:4088/api/v1/interceptor/handle"}
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, unrelatedBody, "authorization", "admin"), 200);

        // The unrelated write must not have dropped the previously-applied catalog schema.
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-survive", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    void testApplyCatalogSchemaPublicBucketRejected() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/public/apply-catalog-schema-public",
                      "spec": {"type": "object"}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
    }

    @Test
    @SneakyThrows
    void testApplyCatalogSchemaDuplicateIdWithinBatchRejected() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-dup-a",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    },
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-dup-b",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        // Caught at precheck (default precheck=true): the first entry registers the $id in
        // scratch, the second collides with it — same skip/fail collapse as
        // testApplyPrecheckMixedBatchRejectedAtomically, so nothing is actually written.
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("SKIPPED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(1).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(1).get("error").asText().contains("apply-dup-id"),
                () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-dup-a", null, "",
                "authorization", "admin"), 404);
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-dup-b", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyCatalogSchemaDuplicateIdPrecheckFalsePartialResults() {
        // precheck=false skips validateOnly entirely and writes entries as it goes: the first
        // entry is really applied (and its $id lands in scratch), so the second one's real-apply
        // conflict check (schemaConflictMessage, same helper the precheck path uses) rejects it.
        // No batch-level atomicity — the first entry's write stands.
        String body = """
                {
                  "precheck": false,
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-pf-a",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-pf-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    },
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-pf-b",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-pf-dup-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("APPLIED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(1).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(1).get("error").asText().contains("apply-pf-dup-id"),
                () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-pf-a", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-pf-b", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyCatalogSchemaDuplicateIdAgainstExistingRejected() {
        String firstBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-existing",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-existing-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, firstBody, "authorization", "admin"), 200);

        String secondBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-conflict",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-existing-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;

        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, secondBody, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(0).get("error").asText().contains("apply-existing-id"),
                () -> "Body: " + response.body());
        verify(send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-conflict", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testApplyCatalogSchemaReapplySameIdStillSucceeds() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-reapply",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-reapply-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin"), 200);

        String updatedBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-reapply",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-reapply-id",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Updated model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, updatedBody, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(0, parsed.get("failed").asInt(), () -> "Body: " + response.body());
    }

    @Test
    @SneakyThrows
    void testApplyCatalogSchemaIdChangeRejected() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-id-change",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-id-change-original",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin"), 200);

        String changedBody = """
                {
                  "manifests": [
                    {
                      "kind": "CatalogSchema",
                      "name": "catalog_schemas/platform/apply-catalog-schema-id-change",
                      "spec": {
                        "$schema": "https://dial.epam.com/catalog_schemas/schema#",
                        "$id": "https://dial.epam.com/catalog-schemas/apply-id-change-different",
                        "dial:catalogEntityType": "model",
                        "dial:catalogDisplayName": "Model",
                        "type": "object"
                      }
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, changedBody, "authorization", "admin");
        verify(response, 422);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(0, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        assertEquals(1, parsed.get("failed").asInt(), () -> "Body: " + response.body());
        assertEquals("FAILED", parsed.get("results").get(0).get("status").asText(), () -> "Body: " + response.body());
        assertTrue(parsed.get("results").get(0).get("error").asText().contains("cannot be changed"),
                () -> "Body: " + response.body());

        Response get = send(HttpMethod.GET, "/v1/catalog_schemas/platform/apply-catalog-schema-id-change", null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("apply-id-change-original"),
                () -> "Expected original $id to be unaffected: " + get.body());
    }

    @Test
    @SneakyThrows
    void testApplyApplicationAndToolSet() {
        String body = """
                {
                  "manifests": [
                    {
                      "kind": "Application",
                      "name": "applications/platform/apply-app-1",
                      "spec": {
                        "endpoint": "http://example.com/v1/completions",
                        "display_name": "Apply App"
                      }
                    },
                    {
                      "kind": "ToolSet",
                      "name": "toolsets/platform/apply-toolset-1",
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
        verify(send(HttpMethod.GET, "/v1/applications/platform/apply-app-1", null, "",
                "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/toolsets/platform/apply-toolset-1", null, "",
                "authorization", "admin"), 200);
    }

    @Test
    @SneakyThrows
    void batchKeyRotationRemovesOldSecret() {
        // FINDING #2: applying a key with a changed secret via /v1/admin/apply must revoke the
        // old auth bearer.
        String bodyOld = """
                {
                  "manifests": [
                    {
                      "kind": "Key",
                      "name": "keys/platform/apply-rotate-key",
                      "spec": {"key": "apply-secret-old", "project": "projA", "roles": ["admin"]}
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, bodyOld, "authorization", "admin"), 200);
        verify(send(HttpMethod.GET, "/v1/bucket", null, "", "Api-key", "apply-secret-old"), 200);

        String bodyNew = """
                {
                  "manifests": [
                    {
                      "kind": "Key",
                      "name": "keys/platform/apply-rotate-key",
                      "spec": {"key": "apply-secret-new", "project": "projA", "roles": ["admin"]}
                    }
                  ]
                }
                """;
        verify(send(HttpMethod.POST, "/v1/admin/apply", null, bodyNew, "authorization", "admin"), 200);

        verify(send(HttpMethod.GET, "/v1/bucket", null, "", "Api-key", "apply-secret-old"), 401);
        verify(send(HttpMethod.GET, "/v1/bucket", null, "", "Api-key", "apply-secret-new"), 200);
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
                      "name": "settings/platform/global",
                      "spec": {"globalInterceptors": ["interceptor1"], "retriableErrorCodes": []}
                    }
                  ]
                }
                """;
        Response response = send(HttpMethod.POST, "/v1/admin/apply", null, body, "authorization", "admin");
        verify(response, 200);
        JsonNode parsed = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, parsed.get("applied").asInt(), () -> "Body: " + response.body());
        // U.1 (2026-05-21): /v1/settings/platform/global is blob-only. After a successful apply
        // the blob exists and the GET surfaces the API-projected values; no source field.
        Response get = send(HttpMethod.GET, "/v1/settings/platform/global", null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode settings = ProxyUtil.MAPPER.readTree(get.body());
        assertFalse(settings.has("source"),
                () -> "U.1: source field must not appear in any response: " + get.body());
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
                      "name": "keys/platform/apply-key-1",
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
                          "name": "models/platform/apply-soft-invalid",
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
            assertEquals("APPLIED_INVALID", parsed.get("results").get(0).get("status").asText());

            // Wait for rebuild to surface the invalid record.
            JsonNode found = null;
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (System.nanoTime() < deadline) {
                Response get = send(HttpMethod.GET, "/v1/models/platform/apply-soft-invalid", null, "",
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
