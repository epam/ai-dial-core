package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.service.ResourceService;
import lombok.extern.slf4j.Slf4j;

import java.util.Set;
import java.util.TreeSet;

/**
 * Moves one bucket to the tenant-rooted layout, in the order the pieces have to happen in.
 *
 * <p>The order is the safety property, not a convenience: a bucket is sealed before it is drained so a write
 * cannot slip in behind the drain, drained before it is copied so the copy does not capture stale bytes, and
 * the propagation window is waited out on both sides so no pod is still writing at legacy paths when the copy
 * starts, nor still reading them once it is promoted. Getting that sequence wrong loses writes silently,
 * which is why it lives here rather than in whatever happens to be calling.
 *
 * <p>Every step takes a bucket location and re-derives what it covers, so the steps can be run as separate
 * commands in separate processes — which is what an operator stopping to inspect the store between them does.
 */
@Slf4j
public class BucketMigration {

    /**
     * How the propagation window is waited out. Injectable so a test does not spend it.
     */
    @FunctionalInterface
    public interface Delay {
        void await(long millis) throws InterruptedException;
    }

    private final BucketMigrationRegistry states;
    private final BucketMigrator migrator;
    private final ResourceService resources;
    private final Delay delay;

    public BucketMigration(BucketMigrationRegistry states, BucketMigrator migrator, ResourceService resources) {
        this(states, migrator, resources, Thread::sleep);
    }

    public BucketMigration(BucketMigrationRegistry states, BucketMigrator migrator, ResourceService resources, Delay delay) {
        this.states = states;
        this.migrator = migrator;
        this.resources = resources;
        this.delay = delay;
    }

    /**
     * The bucket asked for, plus every location a copy of it would reach. A migration state is matched by
     * exact location while a copy enumerates by prefix, so the two disagree about {@code public/} and the
     * sub-buckets the platform synthesizes under it unless every one of them is sealed.
     */
    public Set<String> covered(String bucketLocation) {
        Set<String> locations = new TreeSet<>(migrator.locations(bucketLocation));
        locations.add(bucketLocation);
        return locations;
    }

    /**
     * Seals everything the copy will touch, waits for the seal to reach every pod, then drains each bucket's
     * pending writes to the blob store.
     */
    public Set<String> prepare(String bucketLocation) throws InterruptedException {
        // A bucket that has already moved must not be prepared again. Sealing it sends resolution back to
        // the legacy tree while everything written since the promotion is in the tenant one: the drain then
        // matches none of those pending writes and drops them, and the copy that follows puts the stale
        // legacy tree back over the live one. Going back is a rollback, which seals and reverts rather than
        // seals and copies.
        BucketMigrationState state = states.resolve(bucketLocation);
        if (state == BucketMigrationState.MIGRATED) {
            throw new IllegalStateException(("%s has already migrated. Copying it again would restore the "
                    + "bucket to how it looked before it moved; roll it back first if that is the intent")
                    .formatted(bucketLocation));
        }

        Set<String> locations = covered(bucketLocation);
        for (String location : locations) {
            states.seal(location);
        }

        delay.await(states.propagationWindow());

        for (String location : locations) {
            resources.flushBucket(location);
        }

        log.info("Sealed and drained {}", locations);
        return locations;
    }

    /**
     * Copies the bucket, then checks the copy stayed inside what was sealed. A location reached but not
     * sealed was accepting writes while its bytes were being read — promoting on top of that would leave it
     * resolving to a tree the migration has left behind.
     */
    public BucketMigrator.Result copy(String bucketLocation) {
        BucketMigrationState state = states.resolve(bucketLocation);
        if (state != BucketMigrationState.MIGRATING) {
            throw new IllegalStateException(
                    "Seal %s before copying it — it is %s".formatted(bucketLocation, state));
        }

        BucketMigrator.Result result = migrator.copyBucket(bucketLocation);
        for (String location : result.locations()) {
            BucketMigrationState reached = states.resolve(location);
            if (reached != BucketMigrationState.MIGRATING) {
                throw new IllegalStateException(("The copy of %s reached %s, which is %s rather than sealed "
                        + "— seal it and copy again").formatted(bucketLocation, location, reached));
            }
        }

        return result;
    }

    /**
     * Moves resolution across to the copy, and waits for that to reach every pod before returning: until it
     * has, some pod is still answering from the legacy tree.
     */
    public Set<String> finish(String bucketLocation) throws InterruptedException {
        Set<String> locations = covered(bucketLocation);
        for (String location : locations) {
            states.promote(location);
        }

        delay.await(states.propagationWindow());
        log.info("Promoted {}", locations);
        return locations;
    }

    /**
     * Puts a migrated bucket back on the legacy tree, which is a state change rather than a second migration
     * because the copy never removed it. Sealed first, for the same reason the move out was.
     */
    public Set<String> revert(String bucketLocation) throws InterruptedException {
        Set<String> locations = covered(bucketLocation);
        for (String location : locations) {
            states.seal(location);
        }

        delay.await(states.propagationWindow());

        for (String location : locations) {
            states.revert(location);
        }

        delay.await(states.propagationWindow());
        log.info("Reverted {} to the legacy layout", locations);
        return locations;
    }

    /**
     * The whole sequence, and an unseal if any of it fails. Without that a failed copy leaves every location
     * it sealed refusing writes indefinitely, with nothing but an operator noticing to end it.
     *
     * <p>The unseal is the rollback rather than a bare state change, because a failure during the promotion
     * may have moved some locations across already, and a pod that observed one of those is writing to the
     * tenant tree; it has to be stopped and waited out before the bucket goes back to the legacy one. What
     * the copy left behind is harmless — the legacy tree was never touched, and a later attempt overwrites
     * it.
     */
    public BucketMigrator.Result migrate(String bucketLocation) throws InterruptedException {
        prepare(bucketLocation);
        try {
            BucketMigrator.Result result = copy(bucketLocation);
            finish(bucketLocation);
            return result;
        } catch (RuntimeException | InterruptedException e) {
            log.warn("Migration of {} failed, returning it to the legacy layout", bucketLocation, e);
            revert(bucketLocation);
            throw e;
        }
    }
}
