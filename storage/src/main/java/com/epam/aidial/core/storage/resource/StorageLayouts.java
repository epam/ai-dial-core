package com.epam.aidial.core.storage.resource;

import java.util.Objects;

/**
 * Holds the layout every physical path is composed with. Installed once during start-up, before any
 * resource is read or written; process-wide because {@link ResourceDescriptor} is constructed everywhere
 * and carries no configuration of its own.
 *
 * <p>The installation cannot be changed. Installing the layout that is already installed is a no-op —
 * every start-up in one JVM installs, and the test suite boots hundreds — while installing a different
 * one throws. Changing the layout under a serving process would re-address live data out from under the
 * resource cache, the write-behind queue and the per-resource locks, all of which key on the physical
 * path, so there is no public way to do it: the only un-install is package-private and is reached from
 * test sources, which is what lets the comparison suites boot both layouts in one JVM.
 *
 * <p>Two limits of that guard, stated because they are not enforced. <em>The same layout</em> means the
 * same instance: no layout implements {@code equals}, so re-installing an equal but distinct one throws.
 * And the guard covers a change of the installation, not a first installation that arrives late — until
 * something installs, the legacy layout serves as the default, and tests deliberately compose paths
 * against it before installing the tenant-rooted one.
 */
public final class StorageLayouts {

    private static volatile StorageLayout installed;

    private StorageLayouts() {
    }

    public static StorageLayout resolveActive() {
        StorageLayout layout = installed;
        return layout == null ? LegacyStorageLayout.INSTANCE : layout;
    }

    public static void install(StorageLayout layout) {
        Objects.requireNonNull(layout, "The storage layout must not be null");
        StorageLayout current = installed;
        if (current != null && current != layout) {
            throw new IllegalStateException("The storage layout is installed and cannot change at runtime: "
                    + current.getClass().getSimpleName() + " -> " + layout.getClass().getSimpleName());
        }

        installed = layout;
    }

    static void resetForTesting() {
        installed = null;
    }
}
