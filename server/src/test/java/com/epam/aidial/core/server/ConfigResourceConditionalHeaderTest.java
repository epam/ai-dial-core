package com.epam.aidial.core.server;

import com.epam.aidial.core.server.util.ProxyUtil;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.http.HttpMethod;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Slice U.0 (2026-05-20) wire-shape unification — adds RFC 7232 conditional-header coverage to the
 * admin-config single-entity surface and asserts the new metadata-listing route. Companion to
 * {@link ModelWriteApiTest} (per-type happy-path PUT/DELETE) and {@link ConfigEntityWriteApiTest}
 * (multi-type sweep) — this class collects the cross-cutting U.0 assertions in one place:
 *
 * <ul>
 *   <li>POST → 405 with {@code Allow: GET, PUT, DELETE} on every admin-config type
 *   <li>PUT {@code If-None-Match: *} → 412 if exists / 200 if absent
 *   <li>PUT {@code If-Match: <etag>} → 412 on mismatch
 *   <li>PUT bare (no conditional header) → 200 upsert (creates when absent)
 *   <li>GET {@code If-None-Match: <etag>} → 304 on match / 200 on stale
 *   <li>Metadata listing returns {@code ResourceFolderMetadata}, blob-only (file entries hidden)
 *   <li>Settings metadata listing is 405 (singleton has no list surface)
 * </ul>
 */
public class ConfigResourceConditionalHeaderTest extends ResourceBaseTest {

    private static final String MODEL_BODY = """
            {
              "type": "chat",
              "endpoint": "http://localhost:7001/openai/deployments/cond/chat/completions"
            }
            """;

    @Test
    @SneakyThrows
    void testGet304OnMatchingIfNoneMatch() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cond-304", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
        String etag = put.headers().get("etag");
        assertNotNull(etag, () -> "PUT must emit an ETag header: " + put.headers());

        Response notModified = send(HttpMethod.GET, "/v1/models/platform/cond-304", null, "",
                "authorization", "admin", "If-None-Match", etag);
        verify(notModified, 304);
        // 304 carries no body content per RFC 7232.
        assertTrue(notModified.body() == null || notModified.body().isEmpty(),
                () -> "304 must have empty body: " + notModified.body());
    }

    @Test
    void testGet200OnStaleIfNoneMatch() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/cond-stale", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response stale = send(HttpMethod.GET, "/v1/models/platform/cond-stale", null, "",
                "authorization", "admin", "If-None-Match", "\"stale-etag\"");
        verify(stale, 200);
        assertTrue(stale.body().contains("\"name\":\"cond-stale\""),
                () -> "Expected full body on stale If-None-Match: " + stale.body());
    }

    @Test
    void testPost405OnModelsEntityUrl() {
        Response post = send(HttpMethod.POST, "/v1/models/platform/any-name", null, MODEL_BODY,
                "authorization", "admin");
        verify(post, 405);
        assertAllowHeader(post);
    }

    @Test
    void testPost405OnInterceptorsEntityUrl() {
        Response post = send(HttpMethod.POST, "/v1/interceptors/platform/any-name", null,
                "{\"endpoint\": \"http://localhost:7001/x\"}", "authorization", "admin");
        verify(post, 405);
        assertAllowHeader(post);
    }

    @Test
    void testPost405OnRolesEntityUrl() {
        Response post = send(HttpMethod.POST, "/v1/roles/platform/any-name", null,
                "{\"limits\": {}}", "authorization", "admin");
        verify(post, 405);
        assertAllowHeader(post);
    }

    @Test
    void testPost405OnSchemasEntityUrl() {
        Response post = send(HttpMethod.POST, "/v1/schemas/platform/any-name", null,
                "{\"type\": \"object\"}", "authorization", "admin");
        verify(post, 405);
        assertAllowHeader(post);
    }

    @Test
    void testPutIfNoneMatchStarCreates() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cond-create", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*");
        verify(put, 200);
    }

    @Test
    void testPutIfNoneMatchStar412OnExisting() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/cond-exists", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);
        Response again = send(HttpMethod.PUT, "/v1/models/platform/cond-exists", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*");
        verify(again, 412);
    }

    @Test
    void testPutBareUpsertCreates() {
        Response put = send(HttpMethod.PUT, "/v1/models/platform/cond-bare", null, MODEL_BODY,
                "authorization", "admin");
        verify(put, 200);

        Response get = send(HttpMethod.GET, "/v1/models/platform/cond-bare", null, "",
                "authorization", "admin");
        verify(get, 200);
    }

    @Test
    void testPutIfMatch412OnMismatch() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/cond-cas", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response put = send(HttpMethod.PUT, "/v1/models/platform/cond-cas", null, MODEL_BODY,
                "authorization", "admin", "If-Match", "\"wrong-etag\"");
        verify(put, 412);
    }

    @Test
    void testPutIfMatchSucceedsOnCurrentEtag() {
        Response create = send(HttpMethod.PUT, "/v1/models/platform/cond-cas-ok", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*");
        verify(create, 200);
        String etag = create.headers().get("etag");
        assertNotNull(etag, () -> "Expected ETag header on create: " + create.headers());

        String updatedBody = """
                {
                  "type": "chat",
                  "endpoint": "http://localhost:7001/openai/deployments/cond-cas-ok/v2/chat/completions"
                }
                """;
        Response update = send(HttpMethod.PUT, "/v1/models/platform/cond-cas-ok", null, updatedBody,
                "authorization", "admin", "If-Match", etag);
        verify(update, 200);
        String newEtag = update.headers().get("etag");
        assertNotNull(newEtag, () -> "Expected ETag header on update: " + update.headers());
        // ETag is content-hashed by ResourceService — different body must yield a different ETag.
        assertNotEquals(etag, newEtag,
                () -> "Expected ETag to change after update: was " + etag + ", got " + newEtag);
    }

    @Test
    @SneakyThrows
    void testMetadataListingReturnsResourceFolderMetadata() {
        verify(send(HttpMethod.PUT, "/v1/models/platform/cond-listed", null, MODEL_BODY,
                "authorization", "admin", "If-None-Match", "*"), 200);

        Response list = send(HttpMethod.GET, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        verify(list, 200);
        JsonNode body = ProxyUtil.MAPPER.readTree(list.body());
        assertEquals("FOLDER", body.get("nodeType").asText());
        JsonNode items = body.get("items");
        assertTrue(items.isArray() && !items.isEmpty(),
                () -> "Expected items in metadata listing: " + list.body());
        boolean found = false;
        for (JsonNode item : items) {
            if ("cond-listed".equals(item.get("name").asText())) {
                assertEquals("ITEM", item.get("nodeType").asText());
                // ResourceType enum serializes as its name (e.g. "MODEL") — matches the user
                // Resource API metadata projection. ETag is not populated on folder-listing
                // entries for compressed types (parity with the existing Resource API metadata).
                assertEquals("MODEL", item.get("resourceType").asText());
                assertEquals("platform", item.get("bucket").asText());
                found = true;
            }
        }
        assertTrue(found, () -> "Expected cond-listed in metadata items: " + list.body());
        // Single-page result has no continuation token. The field is omitted (not null) when absent,
        // matching the existing Resource API metadata envelope.
        assertFalse(body.has("nextToken") && !body.get("nextToken").isNull(),
                () -> "Expected nextToken absent on single-page listing: " + list.body());
    }

    @Test
    void testMetadataListingSettings405() {
        Response response = send(HttpMethod.GET, "/v1/metadata/settings/platform/", null, "",
                "authorization", "admin");
        verify(response, 405);
        // The metadata surface is read-only; the singleton's PUT/DELETE live on the entity URL
        // /v1/settings/platform/global, not on the metadata URL. Allow lists verbs valid on
        // the *requested* resource per RFC 9110 §15.5.6, so only GET.
        assertEquals("GET", response.headers().get("Allow"));
    }

    @Test
    @SneakyThrows
    void testMetadataListingHidesFileEntries() {
        // File fixtures (aidial.config.json) include 'test-model-v1' / 'chat-gpt-35-turbo' /
        // 'embedding-ada' which under U.1 are reachable only via /v1/admin/config/file/...; they
        // must NOT appear in the blob-only metadata listing.
        Response list = send(HttpMethod.GET, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        if (list.status() == 200) {
            JsonNode body = ProxyUtil.MAPPER.readTree(list.body());
            for (JsonNode item : body.get("items")) {
                String name = item.get("name").asText();
                assertTrue(!"test-model-v1".equals(name) && !"chat-gpt-35-turbo".equals(name)
                                && !"embedding-ada".equals(name),
                        () -> "File entry leaked into metadata listing: " + name);
            }
        } else {
            verify(list, 404);
        }
    }

    @Test
    void testMetadataListingForbiddenForNonAdminOnPlatform() {
        verify(send(HttpMethod.GET, "/v1/metadata/interceptors/platform/", null, "",
                "authorization", "user"), 403);
    }

    @Test
    void testMetadataListingInvalidLimitReturns400() {
        verify(send(HttpMethod.GET, "/v1/metadata/models/platform/", "limit=abc", "",
                "authorization", "admin"), 400);
        verify(send(HttpMethod.GET, "/v1/metadata/models/platform/", "limit=1001", "",
                "authorization", "admin"), 400);
    }

    @Test
    void testMetadataListingRejectsNonGetVerbs() {
        Response post = send(HttpMethod.POST, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        verify(post, 405);
        assertEquals("GET", post.headers().get("Allow"));

        Response put = send(HttpMethod.PUT, "/v1/metadata/models/platform/", null, "{}",
                "authorization", "admin");
        verify(put, 405);
        assertEquals("GET", put.headers().get("Allow"));

        Response delete = send(HttpMethod.DELETE, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        verify(delete, 405);
        assertEquals("GET", delete.headers().get("Allow"));
    }

    @Test
    void testMetadataListingUnknownTypeIs404() {
        // public/ is not bound for interceptors per EntityBucketBinding — must be 404.
        verify(send(HttpMethod.GET, "/v1/metadata/interceptors/public/", null, "",
                "authorization", "admin"), 404);
    }

    private static void assertAllowHeader(Response response) {
        String allow = response.headers().get("Allow");
        assertNotNull(allow, () -> "Expected Allow header on 405: " + response.headers());
        assertTrue(allow.contains("GET") && allow.contains("PUT") && allow.contains("DELETE"),
                () -> "Allow header must include GET, PUT, DELETE: " + allow);
        // POST must NOT be advertised on the single-entity surface.
        assertNull(response.headers().get("X-Post-Allowed"));
        assertTrue(!allow.contains("POST"),
                () -> "Allow header must not advertise POST under U.0: " + allow);
    }
}
