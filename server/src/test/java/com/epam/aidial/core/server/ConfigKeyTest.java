package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP integration tests for slice 1S.3: GET reads on the {@code keys} platform-bucket type.
 *
 * <p>Phase 1 has no {@code ?reveal_secrets=true} surface — the secret value is masked with the
 * locked sentinel {@code "***"} for every read (design 04 §2.5–§2.6, polish round 1).
 *
 * <p>U.0 (2026-05-20): per-bucket listings live on the sibling {@code /v1/metadata/...} route and
 * are blob-only — file-sourced keys do not appear in metadata listings.
 */
public class ConfigKeyTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testFileKeyNotAddressableOnPerEntityGet() {
        // U.1 (2026-05-21): per-entity GET is blob-only; file keys are not addressable here.
        Response response = send(HttpMethod.GET, "/v1/keys/platform/proxyKey1", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    @SneakyThrows
    void testFileKeyRequiresSecurityAdminOnFileConfigEndpoint() {
        // U.1: file-config /keys requires security-admin (file map keys equal secrets per OQ-12).
        Response forbidden = send(HttpMethod.GET, "/v1/admin/config/file/keys/proxyKey1", null, "",
                "authorization", "admin");
        verify(forbidden, 403);

        Response allowed = send(HttpMethod.GET, "/v1/admin/config/file/keys/proxyKey1", null, "",
                "authorization", "security-admin");
        verify(allowed, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(allowed.body());
        assertEquals("proxyKey1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        // Secret remains masked under the default response mapper; ?reveal_secrets=true unmasks.
        assertEquals("***", body.get("key").asText(),
                () -> "Secret must be masked without reveal_secrets: " + allowed.body());
        assertEquals("EPM-RTC-GPT", body.get("project").asText());
    }

    @Test
    @SneakyThrows
    void testAdminListsKeysMetadata() {
        // Metadata listing returns ResourceFolderMetadata; with only file-defined keys present,
        // either the folder is empty or absent. No items field exists for the legacy envelope.
        Response response = send(HttpMethod.GET, "/v1/metadata/keys/platform/", null, "",
                "authorization", "admin");
        if (response.status() == 200) {
            JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
            assertEquals("FOLDER", body.get("nodeType").asText());
        } else {
            verify(response, 404);
        }
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/keys/platform/proxyKey1", null, "",
                "authorization", "user"), 403);
    }
}
