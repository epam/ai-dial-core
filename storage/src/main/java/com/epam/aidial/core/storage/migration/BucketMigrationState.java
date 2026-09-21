package com.epam.aidial.core.storage.migration;

/**
 * Where one bucket's data physically lives while the store is migrated to the tenant-rooted layout.
 *
 * <p>{@link #MIGRATING} is the seal. The bucket keeps resolving and reading at its legacy paths — the copy
 * goes to a separate tree — but writes to it are refused, so a copy cannot capture a half-written state.
 */
public enum BucketMigrationState {

    LEGACY,
    MIGRATING,
    MIGRATED;

    public boolean isWritable() {
        return this != MIGRATING;
    }

    /**
     * Every move passes through {@link #MIGRATING}, forwards and back: a bucket is sealed before it is copied,
     * and sealed again before a migrated one is reverted.
     */
    public boolean canTransitionTo(BucketMigrationState next) {
        return switch (this) {
            case LEGACY, MIGRATED -> next == MIGRATING;
            case MIGRATING -> next == LEGACY || next == MIGRATED;
        };
    }
}
