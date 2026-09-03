package com.epam.aidial.core.storage.resource;

/**
 * Supplies the two parts of a physical storage path that differ between storage layouts: the bucket
 * location prefix and the resource-type folder. {@link ResourceDescriptor} composes them with the
 * resource path, which is layout-independent.
 */
public interface StorageLayout {

    String resolveLocationPrefix(String bucketLocation);

    String resolveTypeFolder(String typeGroup);
}
