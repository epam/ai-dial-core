package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.server.data.folder.ComplexResourceRef;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.annotations.VisibleForTesting;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;

/**
 * Periodic sweep that reclaims storage for the folder-as-resource machinery: it finishes tombstoned
 * ({@code deleting}) resources and GCs {@code v/{versionId}} siblings left behind by a failed/interrupted
 * inline delete. It enumerates every complex resource in the system via the {@code complex_resource_refs}
 * reference registry (written by {@link ComplexResourceService#put}), so it never has to walk every
 * bucket.
 *
 * <p>Cursor advancement (sequential, must be exclusive cluster-wide) is decoupled from batch processing:
 * a brief {@link LockService#tryLock(String)} claims and advances the shared cursor, then the (potentially slow)
 * per-item work runs outside that lock. Like {@code ResourceService.sync()}/{@code cleanupTempFolder},
 * {@code TimerService.scheduleWithFixedDelay} fires unconditionally every {@code period}, so ticks on this
 * same instance can overlap if a batch takes longer than {@code period} to process; {@link Settings#activeBatches}
 * is this instance's own concurrency budget for that, enforced purely locally via {@link #activeBatchCount},
 * independent of what any other instance is doing.
 */
@Slf4j
public class ComplexResourceSweepService implements AutoCloseable {

    private static final String CURSOR_STATE_KEY = "cursor_state";

    private final BlobStorage blobStorage;
    private final RedissonClient redis;
    private final LockService lockService;
    private final ComplexResourceService complexResourceService;
    private final EncryptionService encryptionService;
    private final Settings settings;
    private final TimerService.Timer timer;
    private final AtomicInteger activeBatchCount = new AtomicInteger(0);
    private final String cursorStateKey;

    private final Counter batchesCounter;
    private final Counter reclaimedCounter;
    private final Counter orphanVersionsCounter;
    private final Counter danglingRefsCounter;

    public ComplexResourceSweepService(TimerService timerService, BlobStorage blobStorage, RedissonClient redis,
                                       LockService lockService, ComplexResourceService complexResourceService,
                                       EncryptionService encryptionService, Settings settings) {
        this.blobStorage = blobStorage;
        this.redis = redis;
        this.lockService = lockService;
        this.complexResourceService = complexResourceService;
        this.encryptionService = encryptionService;
        this.settings = settings;

        this.batchesCounter = Counter.builder("dial_complex_resource_sweep_batches_total").register(Metrics.globalRegistry);
        this.reclaimedCounter = Counter.builder("dial_complex_resource_sweep_reclaimed_total").register(Metrics.globalRegistry);
        this.orphanVersionsCounter = Counter.builder("dial_complex_resource_sweep_orphan_versions_total").register(Metrics.globalRegistry);
        this.danglingRefsCounter = Counter.builder("dial_complex_resource_sweep_dangling_refs_total").register(Metrics.globalRegistry);

        this.timer = timerService.scheduleWithFixedDelay(settings.getPeriod(), settings.getPeriod(), this::tick);
        String prefix = lockService.getPrefix();
        this.cursorStateKey = "complex_resource:" + BlobStorageUtil.toStoragePath(prefix, CURSOR_STATE_KEY);
    }

    @SneakyThrows
    @Override
    public void close() {
        timer.close();
    }

    @VisibleForTesting
    void tick() {
        if (activeBatchCount.incrementAndGet() > settings.getActiveBatches()) {
            // This instance is already at its own activeBatches cap; skip this tick.
            activeBatchCount.decrementAndGet();
            return;
        }
        try {
            List<? extends StorageMetadata> batch = claimBatch();
            if (batch == null) {
                return;
            }
            batchesCounter.increment();
            for (StorageMetadata meta : batch) {
                try {
                    processRef(meta);
                } catch (Throwable e) {
                    log.warn("Failed to process complex resource ref: {}", meta.getName(), e);
                }
            }
        } catch (Throwable e) {
            log.warn("Failed to run complex resource sweep tick", e);
        } finally {
            activeBatchCount.decrementAndGet();
        }
    }

    /**
     * Claims (and advances) the next batch slice under a brief, exclusive lock, then releases the lock
     * before returning so a concurrent tick can already claim the next slice while this one processes its
     * own. Returns {@code null} if another tick is mid-claim. Once a pass completes (the cursor wraps back
     * to {@code null}), the next tick immediately starts a new pass from the beginning.
     */
    @Nullable
    private List<? extends StorageMetadata> claimBatch() {
        try (LockService.Lock lock = lockService.tryLock(cursorStateKey)) {
            if (lock == null) {
                return null;
            }
            ScanState state = readState();
            PageSet<? extends StorageMetadata> page = blobStorage.list(
                    ComplexResourceService.COMPLEX_RESOURCE_REFS_FOLDER, state.getNextToken(), settings.getBatch(), true);
            state.setNextToken(page.getNextMarker());
            writeState(state);
            return new ArrayList<>(page);
        }
    }

    private void processRef(StorageMetadata meta) {
        if (meta.getType() != StorageType.BLOB) {
            return;
        }
        String path = meta.getName();
        ComplexResourceRef ref = loadRef(path);
        if (ref == null) {
            return;
        }
        ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(ref.getUrl(), encryptionService);
        FolderResourceMarker marker = complexResourceService.readMarkerForSweep(resource);
        if (marker == null) {
            blobStorage.delete(path);
            danglingRefsCounter.increment();
        } else if (ComplexResourceService.STATE_DELETING.equals(marker.getState())) {
            if (complexResourceService.reclaimDeletingResource(resource, settings.getGracePeriod())) {
                blobStorage.delete(path);
                reclaimedCounter.increment();
            }
        } else if (complexResourceService.gcObsoleteVersions(resource, settings.getGracePeriod())) {
            orphanVersionsCounter.increment();
        }
    }


    @Nullable
    private ComplexResourceRef loadRef(String path) {
        Blob blob = blobStorage.load(path);
        if (blob == null) {
            return null;
        }
        try (InputStream input = blob.getPayload().openStream()) {
            return ProxyUtil.convertToObject(input.readAllBytes(), ComplexResourceRef.class);
        } catch (Exception e) {
            log.warn("Failed to read complex resource ref: {}", path, e);
            return null;
        }
    }

    private ScanState readState() {
        RBucket<String> bucket = redis.getBucket(cursorStateKey, StringCodec.INSTANCE);
        String json = bucket.get();
        ScanState state = json == null ? null : ProxyUtil.convertToObject(json, ScanState.class);
        return state == null ? new ScanState() : state;
    }

    private void writeState(ScanState state) {
        RBucket<String> bucket = redis.getBucket(cursorStateKey, StringCodec.INSTANCE);
        bucket.set(Objects.requireNonNull(ProxyUtil.convertToString(state)));
    }

    @Data
    @NoArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class ScanState {
        private String nextToken;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /**
         * Period in milliseconds between sweep ticks.
         */
        private long period;
        /**
         * Number of references listed (and potentially processed) per batch.
         */
        private int batch;
        /**
         * Max number of batches this instance may have mid-processing in parallel at any moment. Enforced
         * purely locally (an in-process counter); each instance gets its own independent budget, not a
         * cluster-wide total.
         */
        private int activeBatches;
        /**
         * Minimum age in milliseconds a {@code deleting} marker or a superseded version must reach before
         * the sweep will physically delete it, to protect any reader that started before the
         * tombstone/supersession.
         */
        private long gracePeriod;
    }
}
