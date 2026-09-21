package com.epam.aidial.core.storage.resource;

import com.epam.aidial.core.storage.migration.BucketMigrationState;
import com.epam.aidial.core.storage.migration.BucketMigrationStates;

import java.util.Objects;

/**
 * Holds the layout every physical path is composed with. Installed once during start-up, before any
 * resource is read or written; process-wide because {@link ResourceDescriptor} is constructed everywhere
 * and carries no configuration of its own.
 *
 * <p>The layout is chosen <em>per bucket</em>, not once for the process, because a migration moves one
 * bucket at a time. It is resolved here rather than inside a {@link StorageLayout} because a physical path
 * is composed from a location prefix and a resource-type folder, and only the first is derived from the
 * bucket: choosing the whole layout up front is what keeps the two halves of a path from coming out of
 * different layouts.
 *
 * <p>What is installed is that choice, and it cannot be replaced. Installing the same one again is a no-op
 * — every start-up in one JVM installs, and the test suite boots hundreds — while installing a different
 * one throws. Swapping it under a serving process would re-address live data out from under the resource
 * cache, the write-behind queue and the per-resource locks, all of which key on the physical path, so
 * there is no public way to do it: the only un-install is package-private and is reached from test
 * sources, which is what lets the comparison suites boot both layouts in one JVM.
 *
 * <p>A migration does change which layout a given bucket resolves to, and is meant to: under
 * {@link #installPerBucket} that follows the bucket's migration state, which only moves while the bucket
 * is sealed against writes. The installation is what is fixed, not every answer it gives.
 *
 * <p>Two limits of the guard, stated because they are not enforced. <em>The same</em> means the same
 * layout and state instances — neither implements {@code equals} — so re-installing an equivalent one
 * throws. And the guard covers a change of the installation, not a first installation that arrives late:
 * until something installs, the legacy layout serves as the default, and tests deliberately compose paths
 * against it before installing the tenant-rooted one.
 */
public final class StorageLayouts {

    private static volatile Resolution installed;

    private StorageLayouts() {
    }

    public static StorageLayout resolveFor(String bucketLocation) {
        Resolution resolution = installed;
        return resolution == null ? LegacyStorageLayout.INSTANCE : resolution.resolve(bucketLocation);
    }

    /**
     * Serves the whole store from one layout — a greenfield deployment, where nothing has to be migrated.
     */
    public static void install(StorageLayout layout) {
        install(new WholeStore(layout));
    }

    private static void install(Resolution resolution) {
        Resolution current = installed;
        if (current != null && !current.equals(resolution)) {
            throw new IllegalStateException("The storage layout is installed and cannot change at runtime: "
                    + current.describe() + " -> " + resolution.describe());
        }

        installed = resolution;
    }

    /**
     * Serves each bucket from the layout its data is actually in. A bucket being copied still reads at its
     * legacy paths: the copy goes to a separate tree, and only {@link BucketMigrationState#MIGRATED} moves
     * resolution across.
     */
    public static void installPerBucket(StorageLayout migrated, BucketMigrationStates states) {
        install(new PerBucket(migrated, states));
    }

    static void resetForTesting() {
        installed = null;
    }

    private sealed interface Resolution {

        StorageLayout resolve(String bucketLocation);

        String describe();
    }

    private record WholeStore(StorageLayout layout) implements Resolution {

        private WholeStore {
            Objects.requireNonNull(layout, "The storage layout must not be null");
        }

        @Override
        public StorageLayout resolve(String bucketLocation) {
            return layout;
        }

        @Override
        public String describe() {
            return layout.getClass().getSimpleName() + " for the whole store";
        }
    }

    private record PerBucket(StorageLayout migrated, BucketMigrationStates states) implements Resolution {

        private PerBucket {
            Objects.requireNonNull(migrated, "The storage layout must not be null");
            Objects.requireNonNull(states, "The bucket migration states must not be null");
        }

        @Override
        public StorageLayout resolve(String bucketLocation) {
            return states.resolve(bucketLocation) == BucketMigrationState.MIGRATED
                    ? migrated
                    : LegacyStorageLayout.INSTANCE;
        }

        @Override
        public String describe() {
            return migrated.getClass().getSimpleName() + " per migrated bucket";
        }
    }
}
