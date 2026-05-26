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
 * <p>Slice U.4 (2026-05-25) retired the {@code security-admin} role and the {@code "***"} mask
 * sentinel. The file-config surface now denies the {@code keys} type to every caller — file map
 * keys equal secrets (OQ-12), and there is no operator role separating "admin" from "may read
 * secret-equivalent names." Operators with strict need read {@code aidial.config.json} directly.
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
    void testFileKeysDeniedOnFileConfigEndpoint() {
        // U.4: file-config /keys denied for every caller (admin or otherwise). File map keys equal
        // secrets per OQ-12; the security-admin tier that previously gated this carve-out is gone.
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/keys/proxyKey1", null, "",
                "authorization", "admin");
        verify(response, 403);
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
