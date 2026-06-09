package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.3: GET reads on the {@code roles} platform-bucket type.
 * U.0 (2026-05-20): per-bucket listings live on the sibling {@code /v1/metadata/...} route and are
 * blob-only — file-sourced entries are no longer surfaced in metadata listings.
 */
public class ConfigRoleTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testFileRoleNotAddressableOnPerEntityGet() {
        // U.1 (2026-05-21): per-entity GET is blob-only; the file-defined role is not addressable.
        Response response = send(HttpMethod.GET, "/v1/roles/platform/default", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    @SneakyThrows
    void testFileRoleReadableViaFileConfigEndpoint() {
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/roles/default", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("default", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertTrue(body.has("limits"));
    }

    @Test
    @SneakyThrows
    void testAdminListsRolesMetadata() {
        // U.0: metadata listing is blob-only — file-defined roles do not appear here.
        // The route returns a ResourceFolderMetadata; an empty fixture (no API blobs) yields
        // either a folder with empty items or a 404. Both responses are acceptable.
        Response response = send(HttpMethod.GET, "/v1/metadata/roles/platform/", null, "",
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
        verify(send(HttpMethod.GET, "/v1/roles/platform/default", null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testMissingRoleReturns404() {
        verify(send(HttpMethod.GET, "/v1/roles/platform/no-such-role", null, "",
                "authorization", "admin"), 404);
    }
}
