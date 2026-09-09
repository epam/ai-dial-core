package com.epam.aidial.core.storage.resource;

import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.migration.BucketMigrationStates;

import java.util.function.Function;

/**
 * Holds the layout every physical path is composed with. Installed once during start-up, before any resource
 * is read or written; process-wide because {@link ResourceDescriptor} is constructed everywhere and carries
 * no configuration of its own.
 *
 * <p>The layout is chosen <em>per bucket</em>, not once for the process, because a migration moves one bucket
 * at a time. It is resolved here rather than inside a {@link StorageLayout} because a physical path is
 * composed from a location prefix and a resource-type folder, and only the first is derived from the bucket:
 * choosing the whole layout up front is what keeps the two halves of a path from coming out of different
 * layouts.
 */
public final class StorageLayouts {

    private static volatile Function<String, StorageLayout> layoutByBucket = bucketLocation -> LegacyStorageLayout.INSTANCE;

    private StorageLayouts() {
    }

    public static StorageLayout resolveFor(String bucketLocation) {
        return layoutByBucket.apply(bucketLocation);
    }

    /**
     * Serves the whole store from one layout — a greenfield deployment, where nothing has to be migrated.
     */
    public static void useLayout(StorageLayout layout) {
        layoutByBucket = bucketLocation -> layout;
    }

    /**
     * Serves each bucket from the layout its data is actually in. A bucket being copied still reads at its
     * legacy paths: the copy goes to a separate tree, and only {@link BucketMigrationState#MIGRATED} moves
     * resolution across.
     */
    public static void useLayoutPerBucket(StorageLayout migrated, BucketMigrationStates states) {
        layoutByBucket = bucketLocation -> states.resolve(bucketLocation) == BucketMigrationState.MIGRATED
                ? migrated
                : LegacyStorageLayout.INSTANCE;
    }
}
