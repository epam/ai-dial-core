package com.epam.aidial.core.storage.resource;

import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.migration.BucketMigrationStates;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class StorageLayoutsTest {

    @AfterEach
    public void resetLayout() {
        StorageLayouts.resetForTesting();
    }

    @Test
    public void testLegacyLayoutIsActiveByDefault() {
        assertSame(LegacyStorageLayout.INSTANCE, StorageLayouts.resolveFor("Users/u1/"));
    }

    @Test
    public void testInstalledLayoutCannotChange() {
        StorageLayouts.install(new TenantRootedStorageLayout("acme"));

        assertThrows(IllegalStateException.class, () -> StorageLayouts.install(LegacyStorageLayout.INSTANCE));
        // An equal but distinct layout is a change too: no layout implements equals, so the no-op case
        // below is the same instance, not an equivalent one.
        assertThrows(IllegalStateException.class, () -> StorageLayouts.install(new TenantRootedStorageLayout("acme")));
        // Migrating a store is not something a running deployment can decide to start either.
        assertThrows(IllegalStateException.class, () -> StorageLayouts.installPerBucket(
                new TenantRootedStorageLayout("acme"), BucketMigrationStates.ALL_LEGACY));
    }

    /**
     * Every start-up in one JVM installs, so installing what is already installed is a no-op rather than an
     * error — that is what lets the legacy test suite boot repeatedly.
     */
    @Test
    public void testSameLayoutReinstallIsNoOp() {
        StorageLayouts.install(LegacyStorageLayout.INSTANCE);
        StorageLayouts.install(LegacyStorageLayout.INSTANCE);

        assertSame(LegacyStorageLayout.INSTANCE, StorageLayouts.resolveFor("Users/u1/"));

        StorageLayouts.resetForTesting();

        StorageLayout migrated = new TenantRootedStorageLayout("acme");
        BucketMigrationStates states = bucketLocation -> BucketMigrationState.MIGRATED;
        StorageLayouts.installPerBucket(migrated, states);
        StorageLayouts.installPerBucket(migrated, states);

        assertSame(migrated, StorageLayouts.resolveFor("Users/u1/"));
    }

    @Test
    public void testResetMakesSecondInstallLegal() {
        StorageLayouts.install(new TenantRootedStorageLayout("acme"));
        StorageLayouts.resetForTesting();

        StorageLayout other = new TenantRootedStorageLayout("umbrella");
        StorageLayouts.install(other);

        assertSame(other, StorageLayouts.resolveFor("Users/u1/"));
    }

    @Test
    public void testDescriptorPathFollowsActiveLayout() {
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "notes.txt",
                List.of("documents"), "bucket", "Users/u1/", false);

        assertEquals("Users/u1/files/documents/notes.txt", file.getAbsoluteFilePath());

        StorageLayouts.install(new TenantRootedStorageLayout("acme"));

        assertEquals(".org/acme/.users/u1/.files/documents/notes.txt", file.getAbsoluteFilePath());
    }

    @Test
    public void testOnlyMigratedBucketsFollowTheNewLayout() {
        StorageLayouts.installPerBucket(new TenantRootedStorageLayout("acme"), states(Map.of(
                "Users/moved/", BucketMigrationState.MIGRATED,
                "Users/copying/", BucketMigrationState.MIGRATING)));

        // Both halves of each path come from one layout: a bucket left behind must not pick up ".files" from
        // its migrated neighbour, which would address a path in neither layout.
        assertEquals(".org/acme/.users/moved/.files/notes.txt", path("Users/moved/"));
        // Sealed, not moved: the copy goes to a separate tree, so the bucket still reads where it was.
        assertEquals("Users/copying/files/notes.txt", path("Users/copying/"));
        assertEquals("Users/waiting/files/notes.txt", path("Users/waiting/"));
    }

    @Test
    public void testLocationTheStateDocumentHasNeverHeardOfFollowsTheDefault() {
        // Resolution is an exact match on the location, not a prefix search: it runs on every physical path
        // composition, on an event loop. So a public function app published while public/ is migrated would
        // synthesize public/deployments/<id>/ as a location the document does not list, and it would resolve
        // to the legacy tree beside a parent that has left it. That is why nothing is created while a
        // migration runs — the environment is closed for its duration — and why completing one flips the
        // document's default rather than listing every bucket: afterwards the same unknown location resolves
        // to the tenant tree with everything else.
        StorageLayouts.installPerBucket(new TenantRootedStorageLayout("acme"), states(Map.of(
                "public/", BucketMigrationState.MIGRATED)));
        assertSame(LegacyStorageLayout.INSTANCE, StorageLayouts.resolveFor("public/deployments/published-later/"));

        // A deployment that comes up after the migration was completed, not a second installation into this
        // one: the layout cannot change under a running process.
        StorageLayouts.resetForTesting();

        StorageLayout migrated = new TenantRootedStorageLayout("acme");
        StorageLayouts.installPerBucket(migrated, bucketLocation -> BucketMigrationState.MIGRATED);
        assertSame(migrated, StorageLayouts.resolveFor("public/deployments/published-later/"));
    }

    private static String path(String bucketLocation) {
        return new ResourceDescriptor(ResourceTypes.FILE, "notes.txt", List.of(), "bucket", bucketLocation, false)
                .getAbsoluteFilePath();
    }

    private static BucketMigrationStates states(Map<String, BucketMigrationState> stateByBucket) {
        return bucketLocation -> stateByBucket.getOrDefault(bucketLocation, BucketMigrationState.LEGACY);
    }
}
