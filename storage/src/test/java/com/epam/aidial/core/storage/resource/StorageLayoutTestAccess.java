package com.epam.aidial.core.storage.resource;

/**
 * Test-source bridge to {@link StorageLayouts#resetForTesting()}, for tests outside this package. The
 * reset is package-private so that no main-source code can un-install a layout; each module's test tree
 * carries its own copy of this bridge, because test classes are not shared between modules. It ships in
 * no artifact.
 */
public final class StorageLayoutTestAccess {

    private StorageLayoutTestAccess() {
    }

    public static void reset() {
        StorageLayouts.resetForTesting();
    }
}
