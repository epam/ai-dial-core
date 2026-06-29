package com.epam.aidial.core.server;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

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
        assertEquals(MergedConfigStore.MODE_ABORT, store.getOnInvalidEntity());
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
