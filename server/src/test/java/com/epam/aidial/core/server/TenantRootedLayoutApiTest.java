package com.epam.aidial.core.server;

import com.epam.aidial.core.storage.resource.LegacyStorageLayout;
import com.epam.aidial.core.storage.resource.StorageLayouts;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.json.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the resource API with {@code storageLayout.tenantRooted} enabled: the whole stack — descriptor,
 * cache and blob store — has to agree on the tenant-rooted paths, which unit tests cannot show.
 */
public class TenantRootedLayoutApiTest extends ResourceBaseTest {

    private static final String TENANT = "test-tenant";

    @Override
    protected JsonObject additionalSettingsOverrides() {
        return new JsonObject().put("storageLayout", new JsonObject()
                .put("tenantRooted", true)
                .put("defaultTenant", TENANT));
    }

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testResourceRoundTrip() {
        Response created = resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1);
        assertEquals(200, created.status());

        Response read = resourceRequest(HttpMethod.GET, "/folder/conversation");
        assertEquals(200, read.status());
        assertEquals(CONVERSATION_BODY_1, read.body());
    }

    @Test
    public void testResourceListingAndDeletion() {
        assertEquals(200, resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1).status());

        Response listing = metadata("/folder/");
        assertEquals(200, listing.status());
        assertTrue(listing.body().contains("conversations/" + bucket + "/folder/conversation"),
                () -> "Unexpected listing: " + listing.body());

        assertEquals(200, resourceRequest(HttpMethod.DELETE, "/folder/conversation").status());
        assertEquals(404, resourceRequest(HttpMethod.GET, "/folder/conversation").status());
    }

    @Test
    public void testBlobIsStoredUnderTenantRoot() throws IOException {
        assertEquals(200, resourceRequest(HttpMethod.PUT, "/folder/conversation", CONVERSATION_BODY_1).status());
        // the resource is written back to the blob store asynchronously
        Response flushed = resourceRequest(HttpMethod.GET, "/folder/conversation");
        assertEquals(200, flushed.status());

        List<Path> storedPaths = findStoredPaths();
        List<Path> tenantRootedPaths = storedPaths.stream()
                .filter(path -> path.toString().contains(".org/" + TENANT))
                .toList();
        assertFalse(tenantRootedPaths.isEmpty(),
                () -> "No blob stored under the tenant root, found: " + storedPaths);
        assertTrue(tenantRootedPaths.stream().anyMatch(path -> path.toString().contains(".conversations")),
                () -> "Conversations are not stored in a reserved type folder: " + tenantRootedPaths);
    }

    private List<Path> findStoredPaths() throws IOException {
        try (Stream<Path> paths = Files.walk(testDir)) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }
}
