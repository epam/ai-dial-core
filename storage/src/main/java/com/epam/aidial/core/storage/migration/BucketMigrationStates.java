package com.epam.aidial.core.storage.migration;

/**
 * Read side of the per-bucket migration state. Consulted on every physical path composition and on every
 * resource write, both of which run on an event loop — so an implementation answers from memory and never
 * does I/O.
 */
@FunctionalInterface
public interface BucketMigrationStates {

    /**
     * The state of a store that has never been migrated, and the state every deployment runs with until a
     * migration is started.
     */
    BucketMigrationStates ALL_LEGACY = bucketLocation -> BucketMigrationState.LEGACY;

    BucketMigrationState resolve(String bucketLocation);
}
