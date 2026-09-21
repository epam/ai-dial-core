package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

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
        assertSame(LegacyStorageLayout.INSTANCE, StorageLayouts.resolveActive());
    }

    @Test
    public void testInstalledLayoutCannotChange() {
        StorageLayouts.install(new TenantRootedStorageLayout("acme"));

        assertThrows(IllegalStateException.class, () -> StorageLayouts.install(LegacyStorageLayout.INSTANCE));
        // An equal but distinct layout is a change too: no layout implements equals, so the no-op case
        // above is the same instance, not an equivalent one.
        assertThrows(IllegalStateException.class, () -> StorageLayouts.install(new TenantRootedStorageLayout("acme")));
    }

    /**
     * Every start-up in one JVM installs, so installing the layout that is already active is a no-op
     * rather than an error — that is what lets the legacy test suite boot repeatedly.
     */
    @Test
    public void testSameLayoutReinstallIsNoOp() {
        StorageLayouts.install(LegacyStorageLayout.INSTANCE);
        StorageLayouts.install(LegacyStorageLayout.INSTANCE);

        assertSame(LegacyStorageLayout.INSTANCE, StorageLayouts.resolveActive());
    }

    @Test
    public void testResetMakesSecondInstallLegal() {
        StorageLayouts.install(new TenantRootedStorageLayout("acme"));
        StorageLayouts.resetForTesting();

        StorageLayout other = new TenantRootedStorageLayout("umbrella");
        StorageLayouts.install(other);

        assertSame(other, StorageLayouts.resolveActive());
    }

    @Test
    public void testDescriptorPathFollowsActiveLayout() {
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "notes.txt",
                List.of("documents"), "bucket", "Users/u1/", false);

        assertEquals("Users/u1/files/documents/notes.txt", file.getAbsoluteFilePath());

        StorageLayouts.install(new TenantRootedStorageLayout("acme"));

        assertEquals(".org/acme/.users/u1/.files/documents/notes.txt", file.getAbsoluteFilePath());
    }
}
