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

import static com.epam.aidial.core.server.util.ResourceDescriptorFactory.fromDecoded;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
    void testFileModelStillReadable() {
        Response response = send(HttpMethod.GET, "/v1/models/public/test-model-v1", null, "",
                "authorization", "admin");
        verify(response, 200);
        assertTrue(response.body().contains("\"name\":\"test-model-v1\""));
        assertTrue(response.body().contains("\"source\":\"file\""));
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
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION,
                blobName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Config merged = dial.getProxy().getConfigStore().get();
        Model blobModel = merged.getModels().get("models/public/" + blobName);
        assertNotNull(blobModel, () -> "Expected canonical-ID key in merged Config: " + merged.getModels().keySet());
        assertEquals(blobName, blobModel.getName(), "Entity.name must be the simple name");
        assertNotNull(merged.getModels().get("test-model-v1"), "File model must still coexist by simple name");

        Response get = send(HttpMethod.GET, "/v1/models/public/" + blobName, null, "",
                "authorization", "admin");
        verify(get, 200);
        assertTrue(get.body().contains("\"name\":\"" + blobName + "\""),
                () -> "Expected simple name in projection: " + get.body());
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
        assertEquals(blobName, blob.getName());
        assertNotNull(merged.getInterceptors().get("interceptor1"), "File interceptor must still coexist");
    }

    @Test
    void testReloadConfigSucceedsUnderMergedStore() {
        Response resp = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, resp.status());
        assertTrue(resp.body().contains("\"models\""), () -> "Expected models in body: " + resp.body());
    }

    @Test
    void testBlobModelSurfacesUnderListing() {
        String blobName = "list-blob-model";
        String body = """
                {
                    "type": "chat",
                    "endpoint": "http://localhost:7001/openai/deployments/list-blob/chat/completions"
                }
                """;
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION,
                blobName, body);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Response list = send(HttpMethod.GET, "/v1/models/public/", null, "",
                "authorization", "admin");
        verify(list, 200);
        assertTrue(list.body().contains("\"name\":\"" + blobName + "\""),
                () -> "Expected simple name in listing: " + list.body());
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
        putBlob(ResourceTypes.MODEL, ResourceDescriptor.PUBLIC_BUCKET, ResourceDescriptor.PUBLIC_LOCATION,
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
        assertNull(merged.getModels().get("models/public/" + blobName),
                "post-reload blob must not surface without an explicit reload (debounce was leaked)");
    }

    @Test
    void testRebuildPreservesApiKeyAuth() {
        // proxyKey1 is defined in aidial.config.json. After MergedConfigStore wiring,
        // ConfigPostProcessor (invoked by MergedConfigStore) is the sole owner of
        // ApiKeyStore.addProjectKeys. A reload must keep the file-defined api-key valid.
        Response resp = send(HttpMethod.GET, "/v1/models/public/test-model-v1");
        verify(resp, 200);

        Response reload = operationRequest("/v1/ops/config/reload", null, "Authorization", "admin");
        assertEquals(200, reload.status());

        Response after = send(HttpMethod.GET, "/v1/models/public/test-model-v1");
        verify(after, 200);
    }

    private void putBlob(ResourceTypes type, String bucket, String location, String name, String body) {
        ResourceService resourceService = dial.getProxy().getResourceService();
        ResourceDescriptor descriptor = fromDecoded(type, bucket, location, name);
        resourceService.putResource(descriptor, body, EtagHeader.ANY, null, false);
    }
}
