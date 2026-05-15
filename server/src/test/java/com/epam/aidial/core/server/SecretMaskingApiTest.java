package com.epam.aidial.core.server;

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
 * Integration coverage for slice 2S.10's masking surface: project-key listings, model listings
 * (including upstreams), and the admin export must emit {@code "***"} for every
 * {@code @EncryptedField}-marked value. The default test config (used by
 * {@link ResourceBaseTest}) already contains keys/upstreams with secrets, so no extra fixture
 * is needed.
 */
public class SecretMaskingApiTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminExportMasksKeyKey() {
        Response resp = adminGet("/v1/admin/export");
        verify(resp, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
        JsonNode keys = body.get("keys");
        assertTrue(keys != null && keys.isObject() && !keys.isEmpty(),
                () -> "expected non-empty keys map: " + resp.body());
        keys.fieldNames().forEachRemaining(propertyName ->
                assertTrue(propertyName.startsWith("keys/"),
                        () -> "File-mode plaintext secrets must not appear as JSON property names; "
                                + "found '" + propertyName + "' in: " + resp.body()));
        keys.forEach(keyNode -> {
            if (keyNode.has("key")) {
                assertEquals("***", keyNode.get("key").asText(),
                        () -> "Key.key must be masked: " + keyNode);
            }
        });
    }

    @Test
    @SneakyThrows
    void testAdminExportMasksUpstreamSecrets() {
        Response resp = adminGet("/v1/admin/export");
        verify(resp, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(resp.body());
        JsonNode models = body.get("models");
        assertTrue(models != null && !models.isEmpty(),
                () -> "expected models in admin export: " + resp.body());
        models.forEach(model -> {
            JsonNode upstreams = model.get("upstreams");
            if (upstreams == null || !upstreams.isArray()) {
                return;
            }
            for (JsonNode up : upstreams) {
                if (up.has("key") && !up.get("key").isNull()) {
                    assertEquals("***", up.get("key").asText());
                }
                if (up.has("extraData") && !up.get("extraData").isNull()) {
                    assertEquals("***", up.get("extraData").asText());
                }
            }
        });
    }

    @Test
    @SneakyThrows
    void testProjectKeyListingMasksKey() {
        // Seed an API-managed project key, reload, then list — the listing surface must mask it.
        String name = "secret-mask-key";
        String body = """
                {
                  "key": "super-secret",
                  "project": "test-project",
                  "role": "default"
                }
                """;
        putBlob(ResourceTypes.PROJECT_KEY, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                name, body);
        reload();

        Response resp = adminGet("/v1/keys/platform/" + name);
        verify(resp, 200);
        JsonNode item = ProxyUtil.MAPPER.readTree(resp.body());
        assertEquals("***", item.get("key").asText(),
                () -> "Key.key must be masked in single-item GET: " + resp.body());
        assertFalse(resp.body().contains("super-secret"),
                () -> "raw secret must not appear in response: " + resp.body());
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
