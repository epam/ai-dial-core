package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for the singleton {@code settings} surface at
 * {@code /v1/settings/platform/global}.
 *
 * <p>Slice 1S.3 shipped read-only with all writes 405. Slice 3S.2-settings adds PUT-upsert and
 * idempotent DELETE plus the API-blob projection on GET so {@code source: "api"} becomes
 * reachable. POST stays 405 with {@code Allow: GET, PUT, DELETE} (singleton has no create surface).
 */
public class ConfigSettingsTest extends ResourceBaseTest {

    private static final String SETTINGS_URL = "/v1/settings/platform/global";

    @Test
    @SneakyThrows
    void testGetSingletonReturnsDefaultSource() {
        // The default test config does not populate globalInterceptors — projection must report "default".
        Response response = send(HttpMethod.GET, SETTINGS_URL, null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("global", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("default", body.get("source").asText());
        assertTrue(body.has("globalInterceptors"));
        assertTrue(body.get("globalInterceptors").isArray());
        assertEquals(0, body.get("globalInterceptors").size());
        assertTrue(body.has("retriableErrorCodes"));
        assertTrue(body.get("retriableErrorCodes").isArray());
        assertEquals(0, body.get("retriableErrorCodes").size());
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, SETTINGS_URL, null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testUnknownSingletonNameReturns404() {
        verify(send(HttpMethod.GET, "/v1/settings/platform/something-else", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testListingPathReturns405WithAllow() {
        Response response = send(HttpMethod.GET, "/v1/settings/platform/", null, "",
                "authorization", "admin");
        verify(response, 405);
        assertEquals("GET, PUT, DELETE", response.headers().get("Allow"));
    }

    @Test
    void testPostSingletonReturns405WithAllow() {
        Response response = send(HttpMethod.POST, SETTINGS_URL, null, "{}",
                "authorization", "admin");
        verify(response, 405);
        assertEquals("GET, PUT, DELETE", response.headers().get("Allow"));
    }

    @Test
    @SneakyThrows
    void testPutUpsertsAndGetSurfacesApiSource() {
        String body = """
                {
                  "globalInterceptors": ["interceptor1"],
                  "retriableErrorCodes": [502, 503]
                }
                """;
        Response put = send(HttpMethod.PUT, SETTINGS_URL, null, body,
                "authorization", "admin");
        verify(put, 200);
        JsonNode putBody = ProxyUtil.MAPPER.readTree(put.body());
        assertEquals("global", putBody.get("name").asText());

        Response get = send(HttpMethod.GET, SETTINGS_URL, null, "",
                "authorization", "admin");
        verify(get, 200);
        JsonNode getBody = ProxyUtil.MAPPER.readTree(get.body());
        assertEquals("api", getBody.get("source").asText());
        assertEquals("valid", getBody.get("status").asText());
        assertEquals(1, getBody.get("globalInterceptors").size());
        assertEquals("interceptor1", getBody.get("globalInterceptors").get(0).asText());
        assertEquals(2, getBody.get("retriableErrorCodes").size());
    }

    @Test
    @SneakyThrows
    void testPutIsUpsertOnSecondCall() {
        // Second PUT replaces the blob — preserve-on-omit semantics do NOT apply to settings since
        // it has no encrypted fields. Both fields are atomic per call.
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["a"]}
                """, "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["b", "c"]}
                """, "authorization", "admin"), 200);

        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals("api", body.get("source").asText());
        assertEquals(2, body.get("globalInterceptors").size());
        assertEquals("b", body.get("globalInterceptors").get(0).asText());
    }

    @Test
    @SneakyThrows
    void testDeleteAfterPutRevertsToDefault() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["x"]}
                """, "authorization", "admin"), 200);
        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "admin"), 204);

        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals("default", body.get("source").asText());
        assertEquals(0, body.get("globalInterceptors").size());
    }

    @Test
    void testDeleteOnMissingIsIdempotent204() {
        // No PUT first — DELETE on absent blob still returns 204; design says singleton DELETE is idempotent.
        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "admin"), 204);
        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "admin"), 204);
    }

    @Test
    void testPutOnNonSingletonNameReturns404() {
        verify(send(HttpMethod.PUT, "/v1/settings/platform/something-else", null, "{}",
                "authorization", "admin"), 404);
    }

    @Test
    void testDeleteOnNonSingletonNameReturns404() {
        verify(send(HttpMethod.DELETE, "/v1/settings/platform/something-else", null, "",
                "authorization", "admin"), 404);
    }

    @Test
    void testNonAdminPutForbidden() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, "{}",
                "authorization", "user"), 403);
    }

    @Test
    void testNonAdminDeleteForbidden() {
        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testPutBodyMustBeJsonObject() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, "[]",
                "authorization", "admin"), 400);
    }

    @Test
    void testPutInvalidJsonReturns400() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, "{not-json",
                "authorization", "admin"), 400);
    }

    @Test
    @SneakyThrows
    void testPutEmptyBodyDefaultsToEmptyFields() {
        // {} is a valid singleton update — both fields default to empty per the typed POJO.
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, "{}",
                "authorization", "admin"), 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals("api", body.get("source").asText());
        assertEquals(0, body.get("globalInterceptors").size());
        assertEquals(0, body.get("retriableErrorCodes").size());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/global-interceptor-config.json")
    void testGetSurfacesFileSourceWhenFileDefinesField() {
        Response response = send(HttpMethod.GET, SETTINGS_URL, null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("file", body.get("source").asText());
        assertEquals(1, body.get("globalInterceptors").size());
        assertEquals("global", body.get("globalInterceptors").get(0).asText());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/global-interceptor-config.json")
    void testPutOverridesFileWithApiSource() {
        // File defines globalInterceptors=["global"]; API blob takes precedence per design 02 §4 overlay.
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["api-only"]}
                """, "authorization", "admin"), 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals("api", body.get("source").asText());
        assertEquals(1, body.get("globalInterceptors").size());
        assertEquals("api-only", body.get("globalInterceptors").get(0).asText());
    }

    @Test
    @SneakyThrows
    void testPutDropsUnknownFields() {
        // Settings POJO is @JsonIgnoreProperties(ignoreUnknown = true) — unknown fields don't break the write.
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["q"], "extraField": "ignored"}
                """, "authorization", "admin"), 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals(1, body.get("globalInterceptors").size());
        assertFalse(body.has("extraField"));
    }
}
