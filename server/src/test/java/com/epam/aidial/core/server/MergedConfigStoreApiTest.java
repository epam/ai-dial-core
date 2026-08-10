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
        Model blobModel = merged.getModels().get("models/platform/" + blobName);
        assertNotNull(blobModel, () -> "Expected canonical-ID key in merged Config: " + merged.getModels().keySet());
        // Model.name carries the short name for API-managed entries too, so legacy /openai/models,
        // /openai/deployments, and rate-limit role-limit lookups see the same short form file
        // entries already use. The admin Configuration API GET / listing projection is unaffected
        // — it independently projects the canonical ID (map key).
        assertEquals(blobName, blobModel.getName(),
                "Entity.name carries the short name for API-managed entries");
        assertNotNull(merged.getModels().get("test-model-v1"), "File model must still coexist by simple name");

        Response get = send(HttpMethod.GET, "/v1/models/platform/" + blobName, null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"models/platform/" + blobName + "\""),
                () -> "Expected canonical name in projection: " + get.body());
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
        Interceptor blob = merged.getInterceptors().get("interceptors/platform/" + blobName);
        assertNotNull(blob, () -> "Expected canonical-ID key in merged Config: " + merged.getInterceptors().keySet());
        // API-managed entries carry the short name (last path segment), not the canonical ID.
        assertEquals(blobName, blob.getName());
        assertNotNull(merged.getInterceptors().get("interceptor1"), "File interceptor must still coexist");
    }

    @Test
    void testBlobModelShadowsFileEntryByShortNameAfterReload() {
        // A blob model written under the SAME short name as an existing file-sourced model must
        // replace it in the merged Config, not coexist alongside it: the file entry keyed by the
        // bare short name is removed, and resolving that short name (verbatim or via getModel)
        // hits the blob entity, which is authoritative once migrated.
        String shortName = "test-model-v1";
        String canonicalId = "models/platform/" + shortName;
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
        Model blobModel = merged.getModels().get(canonicalId);
        assertNotNull(blobModel, () -> "Expected canonical-ID key in merged Config: " + merged.getModels().keySet());
        assertNull(merged.getModels().get(shortName),
                () -> "File entry must be shadowed by the migrated blob entity: " + merged.getModels().keySet());
        assertEquals(blobModel, merged.getModel(shortName), "getModel must resolve the short name to the blob entity");
    }

    @Test
    void testBlobInterceptorShadowsFileEntryByShortNameAfterReload() {
        String shortName = "interceptor1";
        String canonicalId = "interceptors/platform/" + shortName;
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
        Interceptor blob = merged.getInterceptors().get(canonicalId);
        assertNotNull(blob, () -> "Expected canonical-ID key in merged Config: " + merged.getInterceptors().keySet());
        assertNull(merged.getInterceptors().get(shortName),
                () -> "File entry must be shadowed by the migrated blob entity: " + merged.getInterceptors().keySet());
        assertEquals(blob, merged.getInterceptor(shortName),
                "getInterceptor must resolve the short name to the blob entity");
    }

    @Test
    void testBlobRoleShadowsFileEntryByShortNameAfterReload() {
        String shortName = "default";
        String canonicalId = "roles/platform/" + shortName;
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
        assertNotNull(merged.getRoles().get(canonicalId),
                () -> "Expected canonical-ID key in merged Config: " + merged.getRoles().keySet());
        assertNull(merged.getRoles().get(shortName),
                () -> "File entry must be shadowed by the migrated blob entity: " + merged.getRoles().keySet());
        assertEquals(merged.getRoles().get(canonicalId), merged.getRole(shortName),
                "getRole must resolve the short name to the blob entity");
    }

    @Test
    void testBlobApplicationShadowsFileEntryByShortNameAfterReload() {
        String shortName = "app";
        String canonicalId = "applications/platform/" + shortName;
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
        assertNotNull(merged.getApplications().get(canonicalId),
                () -> "Expected canonical-ID key in merged Config: " + merged.getApplications().keySet());
        assertNull(merged.getApplications().get(shortName),
                () -> "File entry must be shadowed by the migrated blob entity: " + merged.getApplications().keySet());
        assertEquals(merged.getApplications().get(canonicalId), merged.selectDeployment(shortName),
                "selectDeployment must resolve the short name to the blob entity");
    }

    @Test
    void testBlobToolSetShadowsFileEntryByShortNameAfterReload() {
        String shortName = "git";
        String canonicalId = "toolsets/platform/" + shortName;
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
        assertNotNull(merged.getToolsets().get(canonicalId),
                () -> "Expected canonical-ID key in merged Config: " + merged.getToolsets().keySet());
        assertNull(merged.getToolsets().get(shortName),
                () -> "File entry must be shadowed by the migrated blob entity: " + merged.getToolsets().keySet());
        assertEquals(merged.getToolsets().get(canonicalId), merged.selectDeployment(shortName),
                "selectDeployment must resolve the short name to the blob entity");
    }

    @Test
    void testBlobAppTypeSchemaShadowsFileEntryByIdAfterReload() {
        // App-type/catalog schemas are keyed by $id (file) vs. canonical id (blob), so the
        // blob-shadows-file removal used for name-addressed types (by short name) doesn't apply
        // directly — instead, the migrated blob entity's own $id is used to remove the file entry
        // sharing it, so $id-keyed listings (ApplicationTypeSchemaController) don't surface the
        // schema twice.
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
        String canonicalId = "schemas/platform/" + blobName;
        assertTrue(merged.getApplicationTypeSchemas().containsKey(canonicalId),
                () -> "Expected canonical-ID key in merged Config: " + merged.getApplicationTypeSchemas().keySet());
        assertFalse(merged.getApplicationTypeSchemas().containsKey(fileSchemaId),
                () -> "File entry keyed by $id must be shadowed by the migrated blob entity: "
                        + merged.getApplicationTypeSchemas().keySet());
        assertEquals(merged.getApplicationTypeSchemas().get(canonicalId),
                merged.getCustomApplicationSchema(URI.create(fileSchemaId)),
                "getCustomApplicationSchema must still resolve the $id via the alias index");
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
