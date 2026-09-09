package com.epam.aidial.core.storage.resource;

/**
 * Holds the layout every physical path is composed with. Set once during start-up, before any resource
 * is read or written; process-wide because {@link ResourceDescriptor} is constructed everywhere and
 * carries no configuration of its own.
 */
public final class StorageLayouts {

    private static volatile StorageLayout active = LegacyStorageLayout.INSTANCE;

    private StorageLayouts() {
    }

    public static StorageLayout resolveActive() {
        return active;
    }

    public static void useLayout(StorageLayout layout) {
        active = layout;
    }
}
