package com.epam.aidial.core.storage.resource;

import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.migration.BucketMigrationStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class StorageLayoutsTest {

    @AfterEach
    public void restoreDefaultLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testLegacyLayoutIsActiveByDefault() {
        assertSame(LegacyStorageLayout.INSTANCE, StorageLayouts.resolveFor("Users/u1/"));
    }

    @Test
    public void testActiveLayoutIsReplaceable() {
        StorageLayout tenantRooted = new TenantRootedStorageLayout("acme");
        StorageLayouts.useLayout(tenantRooted);

        assertSame(tenantRooted, StorageLayouts.resolveFor("Users/u1/"));
    }

    @Test
    public void testDescriptorPathFollowsActiveLayout() {
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "notes.txt",
                List.of("documents"), "bucket", "Users/u1/", false);

        assertEquals("Users/u1/files/documents/notes.txt", file.getAbsoluteFilePath());

        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));

        assertEquals(".org/acme/.users/u1/.files/documents/notes.txt", file.getAbsoluteFilePath());
    }

    @Test
    public void testOnlyMigratedBucketsFollowTheNewLayout() {
        StorageLayouts.useLayoutPerBucket(new TenantRootedStorageLayout("acme"), states(Map.of(
                "Users/moved/", BucketMigrationState.MIGRATED,
                "Users/copying/", BucketMigrationState.MIGRATING)));

        // Both halves of each path come from one layout: a bucket left behind must not pick up ".files" from
        // its migrated neighbour, which would address a path in neither layout.
        assertEquals(".org/acme/.users/moved/.files/notes.txt", path("Users/moved/"));
        // Sealed, not moved: the copy goes to a separate tree, so the bucket still reads where it was.
        assertEquals("Users/copying/files/notes.txt", path("Users/copying/"));
        assertEquals("Users/waiting/files/notes.txt", path("Users/waiting/"));
    }

    private static String path(String bucketLocation) {
        return new ResourceDescriptor(ResourceTypes.FILE, "notes.txt", List.of(), "bucket", bucketLocation, false)
                .getAbsoluteFilePath();
    }

    private static BucketMigrationStates states(Map<String, BucketMigrationState> stateByBucket) {
        return bucketLocation -> stateByBucket.getOrDefault(bucketLocation, BucketMigrationState.LEGACY);
    }
}
