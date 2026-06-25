package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * HTTP integration tests for the per-bucket models listing — slice 1S.2 amended by U.0 (2026-05-20)
 * to live at {@code /v1/metadata/models/platform/} and return {@code ResourceFolderMetadata}
 * (same shape as the user Resource API). Listings are now blob-only — file-defined models do not
 * surface here.
 *
 * <p>The empty-path single-entity URL {@code /v1/models/platform/} now returns 404 (no longer a
 * listing surface).
 */
public class ConfigModelListTest extends ResourceBaseTest {

    @Test
    @SneakyThrows
    void testMetadataListingReturnsResourceFolderMetadata() {
        // PUT a model via the API surface so a blob exists in the listing; otherwise the metadata
        // route returns either an empty folder or 404 (both are acceptable under blob-only listings).
        String body = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/listed/chat/completions"
                }
                """;
        verify(send(HttpMethod.PUT, "/v1/models/platform/listed-model", null, body,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response response = send(HttpMethod.GET, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        verify(response, 200);
        JsonNode node = ProxyUtil.MAPPER.readTree(response.body());
        assertEquals("FOLDER", node.get("nodeType").asText());
        JsonNode items = node.get("items");
        boolean found = false;
        for (JsonNode item : items) {
            if ("listed-model".equals(item.get("name").asText())) {
                assertEquals("ITEM", item.get("nodeType").asText());
                // ResourceType enum serializes as its name (e.g. "MODEL") — matches the user
                // Resource API metadata projection.
                assertEquals("MODEL", item.get("resourceType").asText());
                found = true;
                break;
            }
        }
        if (!found) {
            throw new AssertionError("Expected listed-model in metadata items: " + response.body());
        }
    }

    @Test
    void testEmptyPathSingleEntityUrlReturns404() {
        // U.0: the per-entity URL with empty path is no longer a listing surface.
        verify(send(HttpMethod.GET, "/v1/models/platform/", null, "", "authorization", "admin"), 404);
    }

    @Test
    @SneakyThrows
    void testMetadataListingHidesFileEntries() {
        // The fixture aidial.config.json defines several file-sourced models (e.g. test-model-v1).
        // Under U.0 blob-only listings, those file entries must NOT appear in metadata items.
        Response response = send(HttpMethod.GET, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        if (response.status() == 200) {
            JsonNode node = ProxyUtil.MAPPER.readTree(response.body());
            JsonNode items = node.get("items");
            for (JsonNode item : items) {
                String name = item.get("name").asText();
                if ("test-model-v1".equals(name)) {
                    throw new AssertionError("File entry leaked into metadata listing: " + response.body());
                }
            }
        } else {
            // 404 is acceptable when no API blobs exist yet — file entries are correctly hidden either way.
            verify(response, 404);
        }
    }

    @Test
    void testInvalidLimitReturns400() {
        verify(send(HttpMethod.GET, "/v1/metadata/models/platform/", "limit=abc", "",
                "authorization", "admin"), 400);
        verify(send(HttpMethod.GET, "/v1/metadata/models/platform/", "limit=-1", "",
                "authorization", "admin"), 400);
    }

    @Test
    void testLimitAbove1000Rejected() {
        verify(send(HttpMethod.GET, "/v1/metadata/models/platform/", "limit=1001", "",
                "authorization", "admin"), 400);
    }
}
