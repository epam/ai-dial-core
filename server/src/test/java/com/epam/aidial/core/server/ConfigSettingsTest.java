package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HTTP integration tests for the singleton {@code settings} surface at
 * {@code /v1/settings/platform/global}.
 *
 * <p>U.1 (2026-05-21): the singleton is blob-only on the per-entity surface, symmetric with
 * every other admin-config type — {@code GET} returns the blob when present, {@code 404}
 * otherwise. The {@code source} field is retired entirely. File-defined and schema-default
 * values are inspected via {@code /v1/admin/config/file/settings/global} (admin-gated).
 */
public class ConfigSettingsTest extends ResourceBaseTest {

    private static final String SETTINGS_URL = "/v1/settings/platform/global";
    private static final String FILE_SETTINGS_URL = "/v1/admin/config/file/settings/global";

    @Test
    @SneakyThrows
    void testGetReturns404WhenNoApiBlob() {
        // The default test config has no settings blob — per-entity GET is 404.
        Response response = send(HttpMethod.GET, SETTINGS_URL, null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    @SneakyThrows
    void testFileConfigEndpointReturnsDefaultsWhenFileSilent() {
        // File-config view always projects file/default — even when the file defines nothing.
        Response response = send(HttpMethod.GET, FILE_SETTINGS_URL, null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("global", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertFalse(body.has("source"),
                () -> "U.1: source field must not appear in any response: " + response.body());
        assertTrue(body.has("globalInterceptors"));
        assertTrue(body.get("globalInterceptors").isArray());
        assertEquals(0, body.get("globalInterceptors").size());
        assertTrue(body.has("retriableErrorCodes"));
        assertTrue(body.get("retriableErrorCodes").isArray());
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
    void testListingPathGetReturns404() {
        // FINDING #10: a per-entity URL with an empty name is not a listing surface — 404, symmetric
        // with handleSingleGet for the non-singleton types.
        Response response = send(HttpMethod.GET, "/v1/settings/platform/", null, "",
                "authorization", "admin");
        verify(response, 404);
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
    void testPutUpsertsAndGetReturnsBlobValues() {
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
        assertFalse(getBody.has("source"),
                () -> "U.1: source field must not appear in any response: " + get.body());
        assertEquals("valid", getBody.get("status").asText());
        assertEquals(1, getBody.get("globalInterceptors").size());
        assertEquals("interceptor1", getBody.get("globalInterceptors").get(0).asText());
        assertEquals(2, getBody.get("retriableErrorCodes").size());
    }

    @Test
    @SneakyThrows
    void testPutIsUpsertOnSecondCall() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["a"]}
                """, "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["b", "c"]}
                """, "authorization", "admin"), 200);

        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals(2, body.get("globalInterceptors").size());
        assertEquals("b", body.get("globalInterceptors").get(0).asText());
    }

    @Test
    @SneakyThrows
    void testDeleteAfterPutRevertsToFileDefaultProjection() {
        // After DELETE the per-entity GET returns 404 (no blob); the file-config endpoint surfaces
        // the file/default projection (which is empty in the default test fixture).
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["x"]}
                """, "authorization", "admin"), 200);
        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "admin"), 204);

        verify(send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin"), 404);

        JsonNode fileBody = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, FILE_SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals(0, fileBody.get("globalInterceptors").size());
    }

    @Test
    void testDeleteOnMissingIsIdempotent204() {
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
    void testPutEmptyBodyReturns400() {
        // FINDING #5: an empty body is rejected before coercion; explicit "{}" still works (below).
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, "",
                "authorization", "admin"), 400);
    }

    @Test
    @SneakyThrows
    void testPutEmptyBodyDefaultsToEmptyFields() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, "{}",
                "authorization", "admin"), 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals(0, body.get("globalInterceptors").size());
        assertEquals(0, body.get("retriableErrorCodes").size());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/global-interceptor-config.json")
    void testFileConfigEndpointSurfacesFileDefinedFields() {
        // U.1: file-defined values are visible via /v1/admin/config/file/settings/global.
        // The per-entity surface still returns 404 because no API blob exists.
        verify(send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin"), 404);

        Response response = send(HttpMethod.GET, FILE_SETTINGS_URL, null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals(1, body.get("globalInterceptors").size());
        assertEquals("global", body.get("globalInterceptors").get(0).asText());
    }

    @Test
    @SneakyThrows
    @DialConfigLocation("dial-config/global-interceptor-config.json")
    void testPutShadowsFileValuesOnPerEntityGet() {
        // File defines globalInterceptors=["global"]; after PUT the per-entity GET surfaces the blob.
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["api-only"]}
                """, "authorization", "admin"), 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals(1, body.get("globalInterceptors").size());
        assertEquals("api-only", body.get("globalInterceptors").get(0).asText());
        // File view still projects the file-defined values, untouched by the blob.
        JsonNode fileBody = ProxyUtil.MAPPER.readTree(
                send(HttpMethod.GET, FILE_SETTINGS_URL, null, "", "authorization", "admin").body());
        assertEquals(1, fileBody.get("globalInterceptors").size());
        assertEquals("global", fileBody.get("globalInterceptors").get(0).asText());
    }

    @Test
    @SneakyThrows
    void testPutIfNoneMatchStarReturns412WhenSettingsBlobExists() {
        // RFC 7232 create-only gate: with no blob, If-None-Match: * succeeds; a second PUT
        // with the same header must yield 412 because the blob now exists.
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["x"]}
                """, "authorization", "admin", "If-None-Match", "*"), 200);
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["y"]}
                """, "authorization", "admin", "If-None-Match", "*"), 412);
    }

    @Test
    @SneakyThrows
    void testPutIfMatchReturns412OnMismatch() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["x"]}
                """, "authorization", "admin"), 200);
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["y"]}
                """, "authorization", "admin", "If-Match", "\"wrong-etag\""), 412);
    }

    @Test
    @SneakyThrows
    void testPutIfMatchSucceedsOnCurrentEtag() {
        Response create = send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["x"]}
                """, "authorization", "admin");
        verify(create, 200);
        String etag = create.headers().get("etag");
        assertNotNull(etag, () -> "PUT must emit an ETag header: " + create.headers());

        Response update = send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["y"]}
                """, "authorization", "admin", "If-Match", etag);
        verify(update, 200);
    }

    @Test
    @SneakyThrows
    void testDeleteIfMatchReturns412OnMismatch() {
        verify(send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["x"]}
                """, "authorization", "admin"), 200);
        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "admin", "If-Match", "\"wrong-etag\""), 412);
    }

    @Test
    void testDeleteIfMatchOnMissingBlobReturns412() {
        // RFC 7232: If-Match cannot match a non-existent resource — 412 regardless of the etag value.
        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "admin", "If-Match", "\"any-etag\""), 412);
    }

    @Test
    @SneakyThrows
    void testDeleteIfMatchSucceedsOnCurrentEtag() {
        Response create = send(HttpMethod.PUT, SETTINGS_URL, null, """
                {"globalInterceptors": ["x"]}
                """, "authorization", "admin");
        verify(create, 200);
        String etag = create.headers().get("etag");
        assertNotNull(etag);

        verify(send(HttpMethod.DELETE, SETTINGS_URL, null, "",
                "authorization", "admin", "If-Match", etag), 204);
        verify(send(HttpMethod.GET, SETTINGS_URL, null, "",
                "authorization", "admin"), 404);
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
