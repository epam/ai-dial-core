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
 * HTTP integration tests for slice 1S.3: GET reads on the {@code keys} platform-bucket type.
 *
 * <p>Phase 1 has no {@code ?reveal_secrets=true} surface — the secret value is masked with the
 * locked sentinel {@code "***"} for every read (design 04 §2.5–§2.6, polish round 1).
 */
public class ConfigKeyTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testAdminReadsKeyWithMaskedSecret() {
        Response response = send(HttpMethod.GET, "/v1/keys/platform/proxyKey1", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("proxyKey1", body.get("name").asText());
        assertEquals("valid", body.get("status").asText());
        assertEquals("file", body.get("source").asText());
        assertTrue(body.has("key"), () -> "Expected key field with masked value: " + response.body());
        assertEquals("***", body.get("key").asText(),
                () -> "Secret must be masked in Phase 1 reads: " + response.body());
        assertEquals("EPM-RTC-GPT", body.get("project").asText());
    }

    @Test
    @SneakyThrows
    void testAdminListsKeysWithMaskedSecrets() {
        Response response = send(HttpMethod.GET, "/v1/keys/platform/", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("keys", body.get("entityType").asText());
        assertEquals("platform", body.get("bucket").asText());
        JsonNode items = body.get("items");
        assertTrue(items.isArray() && !items.isEmpty());
        for (JsonNode item : items) {
            // Every listed key must have its secret masked — never leak through the listing channel.
            if (item.has("key")) {
                assertEquals("***", item.get("key").asText(),
                        () -> "Secret leak in listing: " + item);
            }
        }
    }

    @Test
    void testNonAdminGetsForbidden() {
        verify(send(HttpMethod.GET, "/v1/keys/platform/proxyKey1", null, "",
                "authorization", "user"), 403);
    }

    @Test
    @SneakyThrows
    void testListingHasEnvelope() {
        Response response = send(HttpMethod.GET, "/v1/keys/platform", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(response.body());
        assertFalse(body.get("hasMore").asBoolean());
        assertFalse(body.has("nextCursor"));
    }
}
