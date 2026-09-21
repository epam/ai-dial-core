package com.epam.aidial.core.storage.resource;

/**
 * The bucket-rooted layout: the location prefix and the resource-type folder are stored verbatim.
 */
public final class LegacyStorageLayout implements StorageLayout {

    public static final LegacyStorageLayout INSTANCE = new LegacyStorageLayout();

    private LegacyStorageLayout() {
    }

    @Override
    public String resolveLocationPrefix(String bucketLocation) {
        return bucketLocation;
    }

    @Override
    public String resolveTypeFolder(String group) {
        return group;
    }
}
