package com.epam.aidial.core.server;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.config.OnInvalidEntity;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.epam.aidial.core.server.util.ResourceDescriptorFactory.fromDecoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for slice 2S.9: invalid-entity sibling store, listing/get
 * surface with {@code status} + {@code validationWarnings}, admin health
 * endpoint reporting {@code skipped[]}, and config-side slash-keyed-name
 * rejection. JSON parse failures on individual blob entities are always
 * per-entity skipped regardless of {@code onInvalidEntity} mode (design 02 §4.1).
 */
public class InvalidEntityApiTest extends ResourceBaseTest {

    // Long enough to pass the envelope length check, but no key decrypts it.
    private static final String UNDECRYPTABLE_SECRET = Base64.getEncoder()
            .encodeToString("bad-secret-ciphertext-that-no-key-decrypts-0123456789".getBytes(StandardCharsets.UTF_8));

    private static final String DANGLING_MODEL = """
            {
                "type": "chat",
                "endpoint": "http://localhost:7001/openai/deployments/dangling/chat/completions",
                "interceptors": ["unknown-interceptor"]
            }
            """;

    @Test
    void testValidBlobModelHasValidStatus() {
        // U.1 (2026-05-21): the source field is retired entirely. The URL itself discloses the source.
        String name = "valid-blob-model";
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, """
                        {
                            "type": "chat",
                            "endpoint": "http://localhost:7001/openai/deployments/blob-model/chat/completions"
                        }
                        """);
        reload();

        Response resp = adminGet("/v1/models/platform/" + name);
        verify(resp, 200);
        assertTrue(resp.body().contains("\"status\":\"valid\""));
        assertFalse(resp.body().contains("\"source\""),
                () -> "U.1: source field must not appear in any response: " + resp.body());
    }

    @Test
    @SneakyThrows
    void testMalformedBlobSurfacesAsInvalidUnderGet() {
        // U.0 (2026-05-20): metadata listings are blob-only and don't project status/source/warnings.
        // The invalid-entity sibling store is still surfaced via the single-entity GET path —
        // see testMalformedBlobSurfacesUnderGetByName below for the canonical assertion. This test
        // keeps coverage of the legacy "listing→invalid" guard by collapsing it to a per-entity GET.
        String name = "broken-blob-model";
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, "{ this is not json ");
        reload();

        Response resp = adminGet("/v1/models/platform/" + name);
        verify(resp, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
        assertEquals("invalid", body.get("status").asText());
        // U.1: source field retired.
        assertFalse(body.has("source"),
                () -> "U.1: source field must not appear in any response: " + resp.body());
        JsonNode warnings = body.get("validationWarnings");
        assertTrue(warnings.isArray() && !warnings.isEmpty(),
                () -> "Expected validationWarnings array: " + resp.body());
    }

    @Test
    @SneakyThrows
    void testMalformedBlobSurfacesUnderGetByName() {
        String name = "broken-get-model";
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, "not json at all");
        reload();

        Response resp = adminGet("/v1/models/platform/" + name);
        verify(resp, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
        assertEquals("invalid", body.get("status").asText());
        assertEquals(name, body.get("name").asText());
    }

    @Test
    @SneakyThrows
    void testHealthEndpointDegradedWhenInvalidEntitiesExist() {
        String name = "broken-health-model";
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, "garbage");
        reload();

        Response health = send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                "authorization", "admin");
        verify(health, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(health.body());
        assertEquals("degraded", body.get("status").asText());
        JsonNode skipped = body.get("skipped");
        assertTrue(skipped.isArray() && !skipped.isEmpty());
        boolean foundEntry = false;
        for (JsonNode entry : skipped) {
            if (("models/platform/" + name).equals(entry.get("id").asText())) {
                assertTrue(entry.get("reason").asText().toLowerCase().contains("parse"),
                        () -> "Expected parse-related reason: " + entry);
                foundEntry = true;
            }
        }
        assertTrue(foundEntry, () -> "Expected skipped entry for blob: " + body);
    }

    @Test
    @SneakyThrows
    void testInvalidEntityClearsAfterFix() {
        String name = "self-healing-model";
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, "broken");
        reload();
        Response broken = adminGet("/v1/models/platform/" + name);
        assertTrue(broken.body().contains("\"status\":\"invalid\""));

        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, """
                        {
                            "type": "chat",
                            "endpoint": "http://localhost:7001/openai/deployments/healed/chat/completions"
                        }
                        """);
        reload();

        Response healed = adminGet("/v1/models/platform/" + name);
        verify(healed, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(healed.body());
        assertEquals("valid", body.get("status").asText());
        Response health = send(HttpMethod.GET, "/v1/admin/health/config", null, "",
                "authorization", "admin");
        JsonNode healthBody = ProxyUtil.MAPPER.readTree(health.body());
        assertEquals("ok", healthBody.get("status").asText());
        assertEquals(0, healthBody.get("skipped").size());
    }

    @Test
    void testConfiguredModeIsAbortByDefault() {
        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        assertEquals(OnInvalidEntity.ABORT, store.getOnInvalidEntity());
    }

    @Test
    @SneakyThrows
    void testRebuildUnderAbort() {
        String name = "dangling-blob-model";
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, DANGLING_MODEL);

        reload();

        assertDanglingModelIsRecorded(name, "authorization", "admin");
    }

    @Test
    @DialConfigLocation("dial-config/dangling-interceptor-file-model.json")
    void testFileModelUnderAbort() {
        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        assertTrue(store.get().getModels().containsKey("valid-model"));

        assertDanglingModelIsRecorded("file-dangling-model", "api-key", "adminKey1");
    }

    @Test
    @SneakyThrows
    void testOtherViolationsStillAbort() {
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                "dangling-and-bad-pricing", """
                        {
                            "type": "chat",
                            "endpoint": "http://localhost:7001/openai/deployments/bad/chat/completions",
                            "interceptors": ["unknown-interceptor"],
                            "pricing": {"unit": "char_without_whitespace", "prompt": "0.1", "completion": "0.5", "cacheRead": "0.01"}
                        }
                        """);

        Response resp = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");

        assertEquals(500, resp.status());
        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        assertFalse(store.get().getModels().isEmpty(), "the previous config is kept");
    }

    @Test
    @SneakyThrows
    void testInterceptorDeleteUnderAbort() {
        verify(send(HttpMethod.PUT, "/v1/interceptors/platform/delete-i1", null,
                "{\"endpoint\": \"http://localhost:7001/intercept\"}", "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, "/v1/models/platform/delete-m", null, """
                {
                    "type": "chat",
                    "endpoint": "http://localhost:7001/openai/deployments/delete-m/chat/completions",
                    "interceptors": ["delete-i1"]
                }
                """, "authorization", "admin"), 200);

        verify(send(HttpMethod.DELETE, "/v1/interceptors/platform/delete-i1", null, "", "authorization", "admin"), 204);

        assertDanglingModelIsRecorded("delete-m", "authorization", "admin");
    }

    @Test
    @SneakyThrows
    void testSoftWriteSurvivesRebuild() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/survives-rebuild", "softValidation=true", DANGLING_MODEL,
                "authorization", "admin"), 200);

        reload();

        assertDanglingModelIsRecorded("survives-rebuild", "authorization", "admin");
    }

    @Test
    @SneakyThrows
    void testInterceptorCreatedLater() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/created-later-m", "softValidation=true",
                modelWithInterceptor("created-later-i"), "authorization", "admin"), 200);
        assertEquals("invalid", modelStatus("created-later-m"));

        verify(adminPutInterceptor("created-later-i"), 200);

        awaitModelStatus("created-later-m", "valid");
        JsonNode health = ProxyUtil.MAPPER.readTree(adminGet("/v1/admin/health/config").body());
        assertEquals("ok", health.get("status").asText(), health::toString);
        assertTrue(adminGet("/openai/models").body().contains("\"created-later-m\""), "the model is listed under its short name");
    }

    @Test
    @SneakyThrows
    void testInterceptorDeletedAndCreatedAgain() {
        verify(adminPutInterceptor("recreated-i"), 200);
        verify(send(HttpMethod.PUT, "/v1/models/platform/recreated-m", null, modelWithInterceptor("recreated-i"),
                "authorization", "admin"), 200);
        verify(send(HttpMethod.DELETE, "/v1/interceptors/platform/recreated-i", null, "", "authorization", "admin"), 204);
        assertEquals("invalid", modelStatus("recreated-m"));

        verify(adminPutInterceptor("recreated-i"), 200);

        awaitModelStatus("recreated-m", "valid");
    }

    private static String modelWithInterceptor(String interceptor) {
        return DANGLING_MODEL.replace("unknown-interceptor", interceptor);
    }

    private Response adminPutInterceptor(String name) {
        return send(HttpMethod.PUT, "/v1/interceptors/platform/" + name, null,
                "{\"endpoint\": \"http://localhost:7001/intercept\"}", "authorization", "admin");
    }

    @SneakyThrows
    private String modelStatus(String name) {
        Response resp = adminGet("/v1/models/platform/" + name);
        verify(resp, 200);
        return ProxyUtil.MAPPER.readTree(resp.body()).get("status").asText();
    }

    @SneakyThrows
    private void awaitModelStatus(String name, String status) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String last = null;
        while (System.nanoTime() < deadline) {
            last = modelStatus(name);
            if (status.equals(last)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("model " + name + " did not reach status " + status + " within 10 s, last: " + last);
    }

    @SneakyThrows
    private void assertDanglingModelIsRecorded(String name, String... auth) {
        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        assertFalse(store.get().getModels().containsKey(name), "a model with a dangling reference is not a deployment");

        Response resp = send(HttpMethod.GET, "/v1/models/platform/" + name, null, "", auth);
        verify(resp, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
        assertEquals("invalid", body.get("status").asText());
        assertEquals("interceptors[0]", body.at("/validationWarnings/0/field").asText());

        JsonNode health = ProxyUtil.MAPPER.readTree(send(HttpMethod.GET, "/v1/admin/health/config", null, "", auth).body());
        assertEquals("degraded", health.get("status").asText());
        List<String> skipped = new ArrayList<>();
        health.get("skipped").forEach(entry -> skipped.add(entry.get("id").asText()));
        assertTrue(skipped.contains("models/platform/" + name), () -> "skipped: " + skipped);

        assertFalse(send(HttpMethod.GET, "/openai/models", null, "", auth).body().contains("\"" + name + "\""));
    }

    @Test
    @DialConfigLocation("dial-config/slash-keyed-names.json")
    void testSlashKeyedFileEntriesAreDroppedAndNotInInvalidEntities() {
        MergedConfigStore store = (MergedConfigStore) dial.getProxy().getConfigStore();
        assertFalse(store.get().getModels().containsKey("bad/model"));
        assertTrue(store.get().getModels().containsKey("valid-model"));
        assertFalse(store.get().getInterceptors().containsKey("bad/interceptor"));
        assertFalse(store.get().getRoles().containsKey("bad/role"));
        // Slash-keyed file names are warn+drop — never recorded in the sibling store.
        assertTrue(store.getInvalidEntities().isEmpty(),
                () -> "Slash-keyed names must not surface in invalidEntities: "
                        + store.getInvalidEntities());
    }

    @Test
    @SneakyThrows
    void testInvalidToolSetOmitsAuthSettingsSecrets() {
        putBlob(ResourceTypes.TOOL_SET, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                "bad-secret-toolset", """
                        {
                          "endpoint": "http://localhost:9876/mcp",
                          "transport": "HTTP",
                          "display_name": "Bad Secret Toolset",
                          "auth_settings": {
                            "authentication_type": "OAUTH",
                            "client_id": "cid",
                            "client_secret": "%s",
                            "code_verifier": "%s"
                          }
                        }
                        """.formatted(UNDECRYPTABLE_SECRET, UNDECRYPTABLE_SECRET));
        reload();

        Response resp = adminGet("/v1/toolsets/platform/bad-secret-toolset");
        verify(resp, 200);
        assertEquals("cid", ProxyUtil.MAPPER.readTree(resp.body()).at("/auth_settings/client_id").asText());
        assertEquals("invalid", ProxyUtil.MAPPER.readTree(resp.body()).get("status").asText());
        assertSecretsOmitted(resp.body());
    }

    @Test
    @SneakyThrows
    void testInvalidApplicationOmitsExternalServiceSecrets() {
        putBlob(ResourceTypes.APPLICATION, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                "bad-secret-app", """
                        {
                          "endpoint": "http://application1/v1/completions",
                          "display_name": "Bad Secret App",
                          "external_services": {
                            "github": {
                              "display_name": "GitHub",
                              "auth_settings": {
                                "authentication_type": "OAUTH",
                                "client_id": "cid",
                                "client_secret": "%s",
                                "code_verifier": "%s"
                              }
                            }
                          }
                        }
                        """.formatted(UNDECRYPTABLE_SECRET, UNDECRYPTABLE_SECRET));
        reload();

        Response resp = adminGet("/v1/applications/platform/bad-secret-app");
        verify(resp, 200);
        assertEquals("GitHub", ProxyUtil.MAPPER.readTree(resp.body()).at("/external_services/github/display_name").asText());
        assertEquals("invalid", ProxyUtil.MAPPER.readTree(resp.body()).get("status").asText());
        assertSecretsOmitted(resp.body());
    }

    @SneakyThrows
    private static void assertSecretsOmitted(String body) {
        assertFalse(body.contains(UNDECRYPTABLE_SECRET), () -> "stored ciphertext returned: " + body);
        for (String key : List.of("client_secret", "code_verifier", "client_secret_hint")) {
            assertFalse(body.contains("\"" + key + "\""), () -> key + " returned: " + body);
        }
    }

    private void reload() {
        Response resp = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, resp.status());
    }

    private Response adminGet(String path) {
        return send(HttpMethod.GET, path, null, "", "authorization", "admin");
    }

    private void putBlob(ResourceTypes type, String bucket, String location, String name, String body) {
        ResourceService resourceService = dial.getProxy().getResourceService();
        ResourceDescriptor descriptor = fromDecoded(type, bucket, location, name);
        resourceService.putResource(descriptor, body, EtagHeader.ANY, null, false);
    }

}
