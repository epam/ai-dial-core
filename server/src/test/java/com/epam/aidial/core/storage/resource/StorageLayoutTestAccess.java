package com.epam.aidial.core.storage.resource;

/**
 * Test-source bridge to {@link StorageLayouts#resetForTesting()}. Deliberately declared in the storage
 * {@code resource} package from the server test tree: the reset is package-private so no main-source
 * code can un-install a layout, and this class is how server tests reach it. It ships in no artifact.
 */
public final class StorageLayoutTestAccess {

    private StorageLayoutTestAccess() {
    }

    public static void reset() {
        StorageLayouts.resetForTesting();
    }
}
