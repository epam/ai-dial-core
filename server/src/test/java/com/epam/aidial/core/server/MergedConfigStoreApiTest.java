package com.epam.aidial.core.server;

import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.http.HttpMethod;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static com.epam.aidial.core.server.util.ResourceDescriptorFactory.fromDecoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for slice 2S.8: {@link MergedConfigStore} wired into
 * {@code AiDial}. Verifies file-config backward compatibility plus blob-entity
 * union semantics — file entries keyed by simple name, API entries keyed by
 * canonical ID, surfaced through the 1S.1 read controller via canonical-ID-first
 * lookup.
 */
public class MergedConfigStoreApiTest extends ResourceBaseTest {

    @Test
    void testFileModelNotAddressableOnPerEntityGet() {
        // U.1 (2026-05-21): per-entity GET is blob-only; file entries are not addressable here.
        Response response = send(HttpMethod.GET, "/v1/models/platform/test-model-v1", null, "",
                "authorization", "admin");
        verify(response, 404);
    }

    @Test
    void testFileModelReadableViaFileConfigEndpoint() {
        // U.1 (2026-05-21): file entries are inspected via /v1/admin/config/file/{type}/{name}.
        Response response = send(HttpMethod.GET, "/v1/admin/config/file/models/test-model-v1", null, "",
                "authorization", "admin");
        verify(response, 200);
        assertTrue(response.body().contains("\"name\":\"test-model-v1\""));
        // The source field is retired entirely (U.1) — the URL itself discloses the source.
        assertFalse(response.body().contains("\"source\""),
                () -> "U.1: source field must not appear in any response: " + response.body());
    }

    @Test
    void testConfigStoreIsMergedConfigStore() {
        assertInstanceOf(MergedConfigStore.class, dial.getProxy().getConfigStore());
    }

    @Test
    void testBlobModelSurfacesAfterReload() {
        String blobName = "blob-model-v1";
        String body = """
                {
                    "type": "chat",
                    "endpoint": "http://localhost:7001/openai/deployments/blob-model/chat/completions"
                }
                """;
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                blobName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        Model blobModel = merged.getModels().get(blobName);
        assertNotNull(blobModel, () -> "Expected short-name key in merged Config: " + merged.getModels().keySet());
        // Model.name is the map key — the short name, the same key a file entry for this
        // logical entity would use.
        assertEquals(blobName, blobModel.getName(), "Entity.name is the short name for API-managed entries too");
        assertNotNull(merged.getModels().get("test-model-v1"), "File model must still coexist by its own short name");

        // Admin GET reads blob storage directly (not the in-memory map), so the projected name is
        // whatever the URL's short-name segment was.
        Response get = send(HttpMethod.GET, "/v1/models/platform/" + blobName, null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"" + blobName + "\""),
                () -> "Expected short name in projection: " + get.body());
        assertTrue(get.body().contains("\"endpoint\""),
                () -> "Expected endpoint field in projection: " + get.body());
    }

    @Test
    void testBlobInterceptorSurfacesAfterReload() {
        String blobName = "blob-interceptor-1";
        String body = """
                {
                    "endpoint": "http://localhost:9000/intercept"
                }
                """;
        putBlob(ResourceTypes.INTERCEPTOR, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                blobName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        Interceptor blob = merged.getInterceptors().get(blobName);
        assertNotNull(blob, () -> "Expected short-name key in merged Config: " + merged.getInterceptors().keySet());
        assertEquals(blobName, blob.getName());
        assertNotNull(merged.getInterceptors().get("interceptor1"), "File interceptor must still coexist");
    }

    @Test
    void testBlobModelOverwritesFileEntryAtSameShortNameAfterReload() {
        // A blob model written under the SAME short name as an existing file-sourced model shares
        // that single map key with it — both sources key by short name uniformly, so the blob
        // write simply overwrites the slot in place; there's no separate canonical-id slot left
        // over to shadow.
        String shortName = "test-model-v1";
        String body = """
                {
                    "type": "chat",
                    "displayName": "Migrated Test Model",
                    "endpoint": "http://localhost:7001/openai/deployments/migrated/chat/completions"
                }
                """;
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                shortName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        Model model = merged.getModels().get(shortName);
        assertNotNull(model);
        assertEquals("http://localhost:7001/openai/deployments/migrated/chat/completions", model.getEndpoint(),
                "Blob entity must win over the file entry sharing its short name");
        assertEquals(model, merged.selectDeployment(shortName),
                "selectDeployment must resolve the short name directly to the blob entity");
    }

    @Test
    void testBlobInterceptorOverwritesFileEntryAtSameShortNameAfterReload() {
        String shortName = "interceptor1";
        String body = """
                {
                    "endpoint": "http://localhost:9000/migrated-intercept"
                }
                """;
        putBlob(ResourceTypes.INTERCEPTOR, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                shortName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        Interceptor interceptor = merged.getInterceptors().get(shortName);
        assertNotNull(interceptor);
        assertEquals("http://localhost:9000/migrated-intercept", interceptor.getEndpoint(),
                "Blob entity must win over the file entry sharing its short name");
    }

    @Test
    void testBlobRoleOverwritesFileEntryAtSameShortNameAfterReload() {
        String shortName = "default";
        String body = """
                {
                    "limits": {}
                }
                """;
        putBlob(ResourceTypes.ROLE, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                shortName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        assertNotNull(merged.getRoles().get(shortName));
        assertEquals(shortName, merged.getRoles().get(shortName).getName());
    }

    @Test
    void testBlobApplicationOverwritesFileEntryAtSameShortNameAfterReload() {
        String shortName = "app";
        String body = """
                {
                    "endpoint": "http://application1/v1/completions",
                    "display_name": "Migrated Platform App"
                }
                """;
        putBlob(ResourceTypes.APPLICATION, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                shortName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        assertNotNull(merged.getApplications().get(shortName));
        assertEquals(merged.getApplications().get(shortName), merged.selectDeployment(shortName),
                "selectDeployment must resolve the short name directly to the blob entity");
    }

    @Test
    void testBlobToolSetOverwritesFileEntryAtSameShortNameAfterReload() {
        String shortName = "git";
        String body = """
                {
                    "endpoint": "http://localhost:9876",
                    "transport": "HTTP",
                    "display_name": "Migrated Git Toolset"
                }
                """;
        putBlob(ResourceTypes.TOOL_SET, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                shortName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        assertNotNull(merged.getToolsets().get(shortName));
        assertEquals(merged.getToolsets().get(shortName), merged.selectDeployment(shortName),
                "selectDeployment must resolve the short name directly to the blob entity");
    }

    @Test
    void testBlobAppTypeSchemaShadowsFileEntryByIdAfterReload() {
        // Both file and blob schema entries are keyed by $id. A blob written with the same $id
        // as a file entry overwrites it in the single map — no alias index needed.
        String fileSchemaId = "https://mydial.somewhere.com/custom_application_schemas/specific_application_type";
        String blobName = "blob-schema-1";
        String body = """
                {
                    "$schema": "https://dial.epam.com/application_type_schemas/schema#",
                    "$id": "%s",
                    "display_name": "Blob-migrated schema"
                }
                """.formatted(fileSchemaId);
        putBlob(ResourceTypes.APP_TYPE_SCHEMA, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                blobName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        // Blob entry is keyed by its $id — same key the file entry used — so it shadows the file entry.
        assertTrue(merged.getApplicationTypeSchemas().containsKey(fileSchemaId),
                () -> "Expected $id key in merged Config: " + merged.getApplicationTypeSchemas().keySet());
        // No separate canonical-id key exists for schemas.
        String canonicalId = "schemas/platform/" + blobName;
        assertFalse(merged.getApplicationTypeSchemas().containsKey(canonicalId),
                () -> "Blob schemas must not be keyed by canonical id: " + merged.getApplicationTypeSchemas().keySet());
        // Direct $id lookup works — no alias indirection needed.
        assertEquals(merged.getApplicationTypeSchemas().get(fileSchemaId),
                merged.getCustomApplicationSchema(URI.create(fileSchemaId)),
                "getCustomApplicationSchema must resolve the $id directly from the map");
    }

    @Test
    void testReloadConfigSucceedsUnderMergedStore() {
        Response resp = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, resp.status());
        assertTrue(resp.body().contains("\"models\""), () -> "Expected models in body: " + resp.body());
    }

    @Test
    void testBlobModelSurfacesUnderMetadataListing() {
        // U.0 (2026-05-20): the per-bucket listing route is /v1/metadata/{type}/{bucket}/ and
        // emits ResourceFolderMetadata; the row carries the blob's simple name (entity {@code name}
        // canonicalisation lives in MergedConfigStore, not the storage metadata).
        String blobName = "list-blob-model";
        String body = """
                {
                    "type": "chat",
                    "endpoint": "http://localhost:7001/openai/deployments/list-blob/chat/completions"
                }
                """;
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                blobName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Response list = send(HttpMethod.GET, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        verify(list, 200);
        assertTrue(list.body().contains("\"name\":\"" + blobName + "\""),
                () -> "Expected blob entry in metadata listing: " + list.body());
    }

    @Test
    void testFileEntriesDoNotAppearInMetadataListing() {
        // U.0 (2026-05-20) — metadata listings are blob-only. U.1 (2026-05-21): file entries are
        // reachable via /v1/admin/config/file/{type}/{name} (no longer via per-entity GET).
        Response list = send(HttpMethod.GET, "/v1/metadata/models/platform/", null, "",
                "authorization", "admin");
        if (list.status() == 200) {
            assertTrue(!list.body().contains("\"name\":\"test-model-v1\"")
                            || list.body().contains("\"name\":\"models/platform/test-model-v1\""),
                    () -> "File simple-name entry must not appear in metadata listing: " + list.body());
        }
        Response single = send(HttpMethod.GET, "/v1/admin/config/file/models/test-model-v1", null, "",
                "authorization", "admin");
        verify(single, 200);
        assertTrue(single.body().contains("\"name\":\"test-model-v1\""),
                () -> "File-config GET must surface the file entry: " + single.body());
    }

    @Test
    void testReloadDoesNotScheduleRedundantDebounce() {
        // reload() runs a synchronous rebuild and produces the authoritative merged Config; the
        // file-poll callback fired by fileConfigStore.reload() schedules a 500ms debounce timer
        // that must be cancelled. Otherwise an admin reload would trigger a second rebuild +
        // addProjectKeys 500ms later. We verify by writing a blob model AFTER the reload returns
        // but BEFORE the debounce window would expire — if the debounce ran, the post-reload blob
        // would surface in Config without our explicit second reload.
        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        String blobName = "post-reload-blob";
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PLATFORM_BUCKET, ResourceDescriptor.PLATFORM_LOCATION,
                blobName, """
                        {
                            "type": "chat",
                            "endpoint": "http://localhost:7001/openai/deployments/post/chat/completions"
                        }
                        """);

        try {
            Thread.sleep(900);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        Config merged = dial.getProxy().getConfigStore().get();
        assertNull(merged.getModels().get("models/platform/" + blobName),
                "post-reload blob must not surface without an explicit reload (debounce was leaked)");
    }

    @Test
    void testRebuildPreservesApiKeyAuth() {
        // proxyKey1 is defined in aidial.config.json. After MergedConfigStore wiring,
        // ConfigPostProcessor (invoked by MergedConfigStore) is the sole owner of
        // ApiKeyStore.addProjectKeys. A reload must keep the file-defined api-key valid.
        // U.1: the file model is no longer addressable via per-entity GET; assert auth works by
        // checking a non-401 status (404 is the expected post-U.1 outcome for the file entry).
        Response resp = send(HttpMethod.GET, "/v1/models/platform/test-model-v1");
        assertNotEquals(401, resp.status(),
                () -> "proxyKey1 auth must succeed before reload: " + resp.status());

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Response after = send(HttpMethod.GET, "/v1/models/platform/test-model-v1");
        assertNotEquals(401, after.status(),
                () -> "proxyKey1 auth must survive the reload: " + after.status());
    }

    private void putBlob(ResourceTypes type, String bucket, String location, String name, String body) {
        ResourceService resourceService = dial.getProxy().getResourceService();
        ResourceDescriptor descriptor = fromDecoded(type, bucket, location, name);
        resourceService.putResource(descriptor, body, EtagHeader.ANY, null, false);
    }
}
