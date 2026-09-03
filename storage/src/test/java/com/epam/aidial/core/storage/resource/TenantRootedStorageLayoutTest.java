package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TenantRootedStorageLayoutTest {

    private final StorageLayout layout = new TenantRootedStorageLayout("acme");

    @AfterEach
    public void restoreLegacyLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testLocationPrefixIsTenantRooted() {
        assertEquals(".org/acme/.users/u1/", layout.resolveLocationPrefix("Users/u1/"));
        assertEquals(".org/acme/.keys/proj/", layout.resolveLocationPrefix("Keys/proj/"));
        assertEquals(".org/acme/", layout.resolveLocationPrefix("public/"));
        assertEquals("", layout.resolveLocationPrefix("platform/"));
    }

    @Test
    public void testTypeFolderIsReserved() {
        assertEquals(".files", layout.resolveTypeFolder("files"));
        assertEquals(".conversations", layout.resolveTypeFolder("conversations"));
    }

    @Test
    public void testSystemLocationsResolve() {
        assertEquals(".system/deployment_cost_stats/", layout.resolveLocationPrefix("deployment_cost_stats/"));
        assertEquals(".system/background_jobs/", layout.resolveLocationPrefix("background_jobs/"));
        assertEquals(".system/response_mappings/", layout.resolveLocationPrefix("response_mappings/"));
    }

    /**
     * The composed path for a system bucket, end to end. These buckets name themselves twice — the location
     * and the resource type carry the same word — which the layout has to preserve rather than tidy up.
     */
    @Test
    public void testSystemBucketPath() {
        StorageLayouts.useLayout(layout);

        ResourceDescriptor job = new ResourceDescriptor(ResourceTypes.BACKGROUND_JOB, "job-1",
                List.of(), ResourceDescriptor.BACKGROUND_JOB_BUCKET,
                ResourceDescriptor.BACKGROUND_JOB_LOCATION, false);

        assertEquals(".system/background_jobs/.background_jobs/job-1", job.getAbsoluteFilePath());
        assertEquals("background_jobs/background_jobs/job-1", job.getStableFilePath());
    }

    /**
     * {@code resolveByPath} re-derives a descriptor from a listed physical path, so it has to parse the
     * tenant-rooted shape, not just compose it.
     */
    @Test
    public void testResolveByPathUnderTenantRootedLayout() {
        StorageLayouts.useLayout(layout);

        ResourceDescriptor folder = new ResourceDescriptor(ResourceTypes.CONVERSATION, null,
                List.of(), "bucket", "Users/u1/", true);
        ResourceDescriptor resolved = folder.resolveByPath(".org/acme/.users/u1/.conversations/chats/chat1");

        assertEquals("chat1", resolved.getName());
        assertEquals(List.of("chats"), resolved.getParentFolders());
        assertEquals(".org/acme/.users/u1/.conversations/chats/chat1", resolved.getAbsoluteFilePath());
    }

    @Test
    public void testUnsupportedLocationRejected() {
        assertThrows(IllegalArgumentException.class, () -> layout.resolveLocationPrefix("Unknown/u1/"));
    }

    @Test
    public void testBlankTenantRejected() {
        assertThrows(IllegalArgumentException.class, () -> new TenantRootedStorageLayout(null));
        assertThrows(IllegalArgumentException.class, () -> new TenantRootedStorageLayout(" "));
    }

    /**
     * The constructor applies the same tenant-id rule the transform applies on every composition, so a
     * misconfigured tenant fails at start-up rather than on the first request.
     */
    @Test
    public void testInvalidTenantRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new TenantRootedStorageLayout("acme/.users"));
        assertThrows(IllegalArgumentException.class, () -> new TenantRootedStorageLayout(".."));
        assertThrows(IllegalArgumentException.class, () -> new TenantRootedStorageLayout(".acme"));
    }
}
