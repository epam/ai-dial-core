package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class TenantRootedStorageLayoutTest {

    private final StorageLayout layout = new TenantRootedStorageLayout("acme");

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
        try {
            ResourceDescriptor job = new ResourceDescriptor(ResourceTypes.BACKGROUND_JOB, "job-1",
                    java.util.List.of(), ResourceDescriptor.BACKGROUND_JOB_BUCKET,
                    ResourceDescriptor.BACKGROUND_JOB_LOCATION, false);

            assertEquals(".system/background_jobs/.background_jobs/job-1", job.getAbsoluteFilePath());
            assertEquals("background_jobs/background_jobs/job-1", job.getStableFilePath());
        } finally {
            StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
        }
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
}
