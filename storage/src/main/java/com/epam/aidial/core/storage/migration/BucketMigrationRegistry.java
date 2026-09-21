package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.SystemResourceRegistry;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.Blob;

import java.io.Closeable;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The per-bucket migration state, authoritative in the blob store and cached in memory.
 *
 * <p>A pod may be up to {@link #propagationWindow()} behind the stored document. That is deliberate: the
 * state is read on the hottest path in the system, where a round trip is not affordable. The migrator closes
 * the gap from the other side — it seals a bucket and waits out the window before touching it, so the
 * interval in which pods disagree about a bucket is an interval in which none of them is writing to it.
 */
@Slf4j
public class BucketMigrationRegistry implements BucketMigrationStates, Closeable {

    /**
     * Sits at the storage root, outside the layout: this document decides the layout, so it cannot be
     * addressed through one.
     */
    private static final String DOCUMENT_PATH = ".dial-migration/bucket-states.json";
    private static final String CONTENT_TYPE = "application/json";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The tops of the legacy layout. Anything under one of these is data a tenant-rooted layout would not
     * find, unless a migration has moved it.
     */
    private static final List<String> LEGACY_ROOTS = legacyRoots();

    private static List<String> legacyRoots() {
        List<String> roots = new ArrayList<>(List.of(
                ResourceDescriptor.USERS_LOCATION_PREFIX, ResourceDescriptor.KEYS_LOCATION_PREFIX,
                ResourceDescriptor.PUBLIC_LOCATION, ResourceDescriptor.PLATFORM_LOCATION));
        for (SystemResourceRegistry system : SystemResourceRegistry.values()) {
            roots.add(system.location());
        }
        return List.copyOf(roots);
    }

    private final BlobStorage blobStore;
    private final LockService lockService;
    private final TimerService.Timer refreshTimer;
    private final long refreshPeriod;

    private volatile Document document = Document.ALL_LEGACY;

    public BucketMigrationRegistry(BlobStorage blobStore, LockService lockService, TimerService timerService, long refreshPeriod) {
        this.blobStore = blobStore;
        this.lockService = lockService;
        this.refreshPeriod = refreshPeriod;
        this.document = load();
        this.refreshTimer = timerService.scheduleWithFixedDelay(refreshPeriod, refreshPeriod, this::refresh);
    }

    @Override
    public BucketMigrationState resolve(String bucketLocation) {
        return document.resolve(bucketLocation);
    }

    /**
     * How long a state change may take to reach every pod. Two refresh periods rather than one: a pod can
     * start a refresh a moment before the write lands, and so observe the document as it was just before it.
     */
    public long propagationWindow() {
        return 2 * refreshPeriod;
    }

    public void seal(String bucketLocation) {
        transition(bucketLocation, BucketMigrationState.MIGRATING);
    }

    public void promote(String bucketLocation) {
        transition(bucketLocation, BucketMigrationState.MIGRATED);
    }

    public void revert(String bucketLocation) {
        transition(bucketLocation, BucketMigrationState.LEGACY);
    }

    /**
     * Compacts the document once nothing listed in it is short of {@link BucketMigrationState#MIGRATED}: the
     * default flips and the per-bucket entries go. From then on a bucket the document has never heard of
     * resolves to the tenant tree with everyone else — which is what makes "new things go to the new storage"
     * true — and the document stops growing with the number of buckets moved.
     *
     * <p>Judges only what is listed. Whether every bucket in the store is listed is the caller's question,
     * and it takes a walk of the store to answer.
     */
    public void complete() {
        lockService.underBucketLock(DOCUMENT_PATH, () -> {
            Document stored = load();
            List<String> unfinished = stored.stateByBucket().entrySet().stream()
                    .filter(entry -> entry.getValue() != BucketMigrationState.MIGRATED)
                    .map(entry -> entry.getKey() + " is " + entry.getValue())
                    .toList();
            if (!unfinished.isEmpty()) {
                throw new IllegalStateException("Cannot declare the store migrated: " + unfinished);
            }

            if (stored.equals(Document.ALL_MIGRATED)) {
                log.debug("The store is already declared migrated");
                return null;
            }

            store(Document.ALL_MIGRATED);
            document = Document.ALL_MIGRATED;
            log.info("Declared the store migrated; every bucket now resolves to the tenant-rooted layout");
            return null;
        });
    }

    @Override
    public void close() {
        try {
            refreshTimer.close();
        } catch (Exception e) {
            log.warn("Failed to stop the bucket migration state refresh", e);
        }
    }

    private void transition(String bucketLocation, BucketMigrationState next) {
        lockService.underBucketLock(DOCUMENT_PATH, () -> {
            Document stored = load();
            BucketMigrationState current = stored.resolve(bucketLocation);
            if (current == next) {
                // Asking for the state a bucket is already in is how an interrupted migration resumes. A
                // step covers several locations and writes one document per location, so a failure part way
                // leaves some of them done; refusing the ones already done would mean the remainder could
                // only be finished by hand.
                log.debug("Bucket {} is already {}", bucketLocation, next);
                return null;
            }

            if (!current.canTransitionTo(next)) {
                throw new IllegalStateException(
                        "Bucket %s cannot go from %s to %s".formatted(bucketLocation, current, next));
            }

            Document updated = stored.with(bucketLocation, next);
            store(updated);
            document = updated;

            log.info("Bucket {} migration state: {} -> {}", bucketLocation, current, next);
            return null;
        });
    }

    @VisibleForTesting
    void refresh() {
        try {
            document = load();
        } catch (Throwable e) {
            // Keeping the previous snapshot is the safe failure: a transient blob error must not read as
            // "every bucket is back to legacy", which would send writes to the tree a bucket has left.
            log.warn("Failed to refresh bucket migration states, keeping the previous ones", e);
        }
    }

    /**
     * Whether any bucket in this store has left the legacy layout. Reads the document once and starts
     * nothing, so a node can ask before deciding whether it is equipped to serve the store at all.
     *
     * <p>Fails the start-up on a blob error rather than assuming the answer: unlike {@link #refresh()}
     * there is no previous snapshot to fall back on, and a pod that does not start is the recoverable
     * outcome.
     */
    public static boolean hasMigratedBuckets(BlobStorage blobStore) {
        Document document = load(blobStore);
        return document.defaultState() != BucketMigrationState.LEGACY
                || document.stateByBucket().values().stream().anyMatch(state -> state != BucketMigrationState.LEGACY);
    }

    /**
     * Whether this store can be served from the tenant-rooted layout outright. It can if it is empty of
     * legacy data — a greenfield deployment — or if a migration has moved every bucket and been declared
     * complete. A store part way through, or one that holds legacy data no migration has touched, would
     * resolve every bucket to a tree with none of its data in it.
     *
     * <p>The migration copies rather than moves, so the legacy tree is still there after it completes;
     * the declaration is what says that tree is no longer the one to serve.
     */
    public static void requireReadyForTenantRootedLayout(BlobStorage blobStore) {
        Document document = load(blobStore);
        if (document.defaultState() == BucketMigrationState.MIGRATED) {
            return;
        }

        if (!document.stateByBucket().isEmpty()) {
            throw new IllegalStateException(("A migration to the tenant-rooted layout is in progress and has "
                    + "not been declared complete: %d bucket(s) are listed in its state. Finish it and declare "
                    + "it complete before enabling storage.layout.tenantRooted, or leave the migration "
                    + "settings in place").formatted(document.stateByBucket().size()));
        }

        for (String root : LEGACY_ROOTS) {
            if (!blobStore.list(root, null, 1, true).isEmpty()) {
                throw new IllegalStateException(("This store holds data on the legacy layout under %s, and no "
                        + "migration has moved it. Serving it tenant-rooted would find none of it; migrate the "
                        + "store first, or leave storage.layout.tenantRooted off").formatted(root));
            }
        }
    }

    @SneakyThrows
    private Document load() {
        return load(blobStore);
    }

    @SneakyThrows
    private static Document load(BlobStorage blobStore) {
        Blob blob = blobStore.load(DOCUMENT_PATH);
        if (blob == null) {
            return Document.ALL_LEGACY;
        }

        try (InputStream stream = blob.getPayload().openStream()) {
            return MAPPER.readValue(stream, Document.class);
        }
    }

    @SneakyThrows
    private void store(Document document) {
        blobStore.store(DOCUMENT_PATH, CONTENT_TYPE, null, Map.of(), MAPPER.writeValueAsBytes(document));
    }

    /**
     * @param stateByBucket state per bucket location, holding only the buckets that differ from the default.
     *                      The migrator compacts it once a whole class of buckets has moved, by flipping the
     *                      default and dropping the entries — otherwise it grows with every bucket migrated.
     */
    record Document(BucketMigrationState defaultState, Map<String, BucketMigrationState> stateByBucket) {

        static final Document ALL_LEGACY = new Document(BucketMigrationState.LEGACY, Map.of());
        static final Document ALL_MIGRATED = new Document(BucketMigrationState.MIGRATED, Map.of());

        @JsonCreator
        Document(@JsonProperty("defaultState") BucketMigrationState defaultState,
                 @JsonProperty("stateByBucket") Map<String, BucketMigrationState> stateByBucket) {
            this.defaultState = defaultState == null ? BucketMigrationState.LEGACY : defaultState;
            this.stateByBucket = stateByBucket == null ? Map.of() : Map.copyOf(stateByBucket);
        }

        BucketMigrationState resolve(String bucketLocation) {
            return stateByBucket.getOrDefault(bucketLocation, defaultState);
        }

        Document with(String bucketLocation, BucketMigrationState state) {
            Map<String, BucketMigrationState> updated = new HashMap<>(stateByBucket);
            if (state == defaultState) {
                updated.remove(bucketLocation);
            } else {
                updated.put(bucketLocation, state);
            }

            return new Document(defaultState, updated);
        }
    }
}
