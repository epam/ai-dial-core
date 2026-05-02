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
 * HTTP integration tests for slice 1S.2: GET /v1/models/public/ listing path.
 * Asserts the unified-config envelope shape ({@code entityType} / {@code bucket} / {@code items}
 * / {@code hasMore}), Public/Owner field projection on items, and Phase 1 forward-compat
 * (per design 03 §4: {@code hasMore: false} always; {@code nextCursor} absent).
 */
public class ConfigModelListTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminSeesEnvelopeWithSourceOnItems() {
        Response response = send(HttpMethod.GET, "/v1/models/public/", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("models", body.get("entityType").asText());
        assertEquals("public", body.get("bucket").asText());
        assertFalse(body.get("hasMore").asBoolean());
        assertFalse(body.has("nextCursor"));
        JsonNode items = body.get("items");
        assertTrue(items.isArray() && !items.isEmpty(), () -> "Expected items array: " + response.body());
        for (JsonNode item : items) {
            assertEquals("valid", item.get("status").asText());
            assertEquals("file", item.get("source").asText());
            assertNotNull(item.get("name"));
        }
    }

    @Test
    @SneakyThrows
    void testUserSeesEnvelopeWithoutSource() {
        Response response = send(HttpMethod.GET, "/v1/models/public/", null, "",
                "authorization", "user");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        JsonNode items = body.get("items");
        assertTrue(items.isArray() && !items.isEmpty());
        for (JsonNode item : items) {
            assertEquals("valid", item.get("status").asText());
            assertFalse(item.has("source"), () -> "source must not appear for non-admin: " + item);
        }
    }

    @Test
    @SneakyThrows
    void testTrailingSlashOptional() {
        Response withSlash = send(HttpMethod.GET, "/v1/models/public/", null, "",
                "authorization", "user");
        Response withoutSlash = send(HttpMethod.GET, "/v1/models/public", null, "",
                "authorization", "user");
        verify(withSlash, 200);
        verify(withoutSlash, 200);
        JsonNode bodyA = ProxyUtil.MAPPER.readTree(withSlash.body());
        JsonNode bodyB = ProxyUtil.MAPPER.readTree(withoutSlash.body());
        assertEquals(bodyA, bodyB);
    }

    @Test
    @SneakyThrows
    void testHasMoreAlwaysFalseOnPhase1() {
        // Fixture defines 5 models; Phase 1 returns the entire snapshot regardless of limit.
        Response response = send(HttpMethod.GET, "/v1/models/public/", "limit=2", "",
                "authorization", "user");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(body.get("hasMore").asBoolean());
        assertFalse(body.has("nextCursor"));
        assertTrue(body.get("items").size() >= 5,
                () -> "Phase 1 must return the full snapshot: " + response.body());
    }

    @Test
    @SneakyThrows
    void testCursorAcceptedAndIgnored() {
        Response withCursor = send(HttpMethod.GET, "/v1/models/public/", "cursor=opaque-token", "",
                "authorization", "user");
        Response withoutCursor = send(HttpMethod.GET, "/v1/models/public/", null, "",
                "authorization", "user");
        verify(withCursor, 200);
        verify(withoutCursor, 200);
        assertEquals(ProxyUtil.MAPPER.readTree(withCursor.body()),
                ProxyUtil.MAPPER.readTree(withoutCursor.body()));
    }

    @Test
    void testInvalidLimitReturns400() {
        verify(send(HttpMethod.GET, "/v1/models/public/", "limit=abc", "", "authorization", "user"), 400);
        verify(send(HttpMethod.GET, "/v1/models/public/", "limit=0", "", "authorization", "user"), 400);
        verify(send(HttpMethod.GET, "/v1/models/public/", "limit=-1", "", "authorization", "user"), 400);
    }

    @Test
    void testLimitAbove500Accepted() {
        // Above-500 must be accepted (clamped per design 03 §4); the clamp itself is internal —
        // Phase 1 returns the full snapshot regardless, so the cap is not response-observable.
        Response response = send(HttpMethod.GET, "/v1/models/public/", "limit=600", "",
                "authorization", "user");
        verify(response, 200);
    }

    @Test
    @SneakyThrows
    void testItemNameSynthesizedFromMapKey() {
        // The fixture's `aidial.config.json` has a model keyed `test-model-v1` whose JSON body has
        // no `name` field — the controller must synthesize it from the map key (design 03 §4).
        Response response = send(HttpMethod.GET, "/v1/models/public/", null, "",
                "authorization", "user");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        boolean found = false;
        for (JsonNode item : body.get("items")) {
            if ("test-model-v1".equals(item.get("name").asText())) {
                found = true;
                break;
            }
        }
        assertTrue(found, () -> "Expected synthesized name=test-model-v1: " + response.body());
    }
}
