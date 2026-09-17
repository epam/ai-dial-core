package com.epam.aidial.core.storage.migration;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
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
import java.util.HashMap;
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
     */
    public static boolean hasMigratedBuckets(BlobStorage blobStore) {
        Document document = load(blobStore);
        return document.defaultState() != BucketMigrationState.LEGACY
                || document.stateByBucket().values().stream().anyMatch(state -> state != BucketMigrationState.LEGACY);
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
