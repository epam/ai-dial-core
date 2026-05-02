package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for slice 1S.6: {@code GET /v1/admin/export} — admin-only full snapshot
 * of in-memory {@link com.epam.aidial.core.config.Config} as JSON or YAML.
 */
public class AdminExportTest extends ResourceBaseTest {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    @Test
    @SneakyThrows
    void testAdminExportsConfigAsJson() {
        Response response = send(HttpMethod.GET, "/v1/admin/export", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertTrue(body.has("models"), () -> "Expected models in export: " + response.body());
        assertTrue(body.has("interceptors"));
        assertTrue(body.has("routes"));
        assertTrue(body.has("roles"));
        assertTrue(body.has("applications"));
        assertTrue(body.has("toolsets"));
        // Keys field is masked at the secret level — present in admin export but never plaintext.
        JsonNode keys = body.get("keys");
        assertNotNull(keys, () -> "Keys must be re-attached in admin export: " + response.body());
        assertTrue(keys.has("proxyKey1"));
        assertEquals("***", keys.get("proxyKey1").get("key").asText(),
                () -> "Secret must be masked in export: " + response.body());
    }

    @Test
    @SneakyThrows
    void testAdminExportsConfigAsYamlViaQueryParam() {
        Response response = send(HttpMethod.GET, "/v1/admin/export", "format=yaml", "",
                "authorization", "admin");
        verify(response, 200);
        // Round-trip the YAML body to confirm it parses and contains expected fields.
        JsonNode body = YAML_MAPPER.readTree(response.body());
        assertTrue(body.has("models"));
        assertTrue(body.has("keys"));
        assertEquals("***", body.get("keys").get("proxyKey1").get("key").asText());
    }

    @Test
    @SneakyThrows
    void testAdminExportsConfigAsYamlViaAcceptHeader() {
        Response response = send(HttpMethod.GET, "/v1/admin/export", null, "",
                "authorization", "admin", "Accept", "application/yaml");
        verify(response, 200);
        JsonNode body = YAML_MAPPER.readTree(response.body());
        assertTrue(body.has("routes"));
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/admin/export", null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testUnauthenticatedGetsRejected() {
        // No authorization header at all — request is rejected before reaching the controller.
        Response response = send(HttpMethod.GET, "/v1/admin/export");
        assertTrue(response.status() == 401 || response.status() == 403,
                () -> "Expected 401/403 for unauthenticated export, got: " + response.status());
    }
}
