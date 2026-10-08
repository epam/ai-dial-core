package com.epam.aidial.core.storage.resource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

public class StorageLayoutsTest {

    @AfterEach
    public void restoreDefaultLayout() {
        StorageLayouts.useLayout(LegacyStorageLayout.INSTANCE);
    }

    @Test
    public void testLegacyLayoutIsActiveByDefault() {
        assertSame(LegacyStorageLayout.INSTANCE, StorageLayouts.resolveActive());
    }

    @Test
    public void testActiveLayoutIsReplaceable() {
        StorageLayout tenantRooted = new TenantRootedStorageLayout("acme");
        StorageLayouts.useLayout(tenantRooted);

        assertSame(tenantRooted, StorageLayouts.resolveActive());
    }

    @Test
    public void testDescriptorPathFollowsActiveLayout() {
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "notes.txt",
                List.of("documents"), "bucket", "Users/u1/", false);

        assertEquals("Users/u1/files/documents/notes.txt", file.getAbsoluteFilePath());

        StorageLayouts.useLayout(new TenantRootedStorageLayout("acme"));

        assertEquals(".org/acme/.users/u1/.files/documents/notes.txt", file.getAbsoluteFilePath());
    }
}
