package com.epam.aidial.core.server.service;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.vertx.stream.InputStreamAdapter;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TempFolderCleaner;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Brings back the archive that {@link AdminSystemExportService} writes: stages and validates the upload, then,
 * once accepted, replaces {@code public/} and {@code platform/} with it.
 *
 * <p>The <b>stage phase</b> ({@link #stage}) reads the ZIP, rejects a bad archive, and writes every blob to
 * {@code .dial-system-import-tmp/<timestamp>/<importId>/<real path>}, outside both buckets. Nothing under
 * {@code public/} or {@code platform/} is touched until the stage phase has accepted the whole archive.
 *
 * <p>The <b>replace phase</b> ({@link #replace}) runs under {@link MergedConfigStore#ADMIN_BUCKET_LOCATIONS}: wipe,
 * promote the staged blobs with server-side copies, drop the queued and cached Redis entries of both buckets, then
 * rebuild the merged config. It is repeatable: importing the same archive again leaves the buckets in the same
 * state.
 */
@Slf4j
public class AdminSystemImportService {

    public static final String STAGING_FOLDER = ".dial-system-import-tmp";

    private static final int PAGE_SIZE = 1000;
    private static final long NANOS_PER_MILLI = 1_000_000L;
    private static final int MAX_BUFFERED_BLOB_BYTES = 5 * 1024 * 1024;

    private final BlobStorage blobStorage;
    private final LockService lockService;
    private final ResourceService resourceService;
    private final MergedConfigStore mergedConfigStore;
    private final AdminSystemImportValidationService validationService;
    private final ContentEncryptionKeyService contentEncryptionKeyService;
    private final AdminSystemImportMetadataReader metadataReader = new AdminSystemImportMetadataReader();
    private final Executor executor;
    private final Settings settings;
    private final LongSupplier clock;
    private final Supplier<String> idGenerator;

    public AdminSystemImportService(BlobStorage blobStorage, LockService lockService, ResourceService resourceService,
                                    MergedConfigStore mergedConfigStore, AdminSystemImportValidationService validationService,
                                    ContentEncryptionKeyService contentEncryptionKeyService, Executor executor, Settings settings,
                                    LongSupplier clock, Supplier<String> idGenerator) {
        this.blobStorage = blobStorage;
        this.lockService = lockService;
        this.resourceService = resourceService;
        this.mergedConfigStore = mergedConfigStore;
        this.validationService = validationService;
        this.contentEncryptionKeyService = contentEncryptionKeyService;
        this.executor = executor;
        this.settings = settings;
        this.clock = clock;
        this.idGenerator = idGenerator;
    }

    /**
     * Takes the system import guard without waiting. {@code null} means another system import holds it; the guard
     * expires on its own after {@link LockService#LOCK_PERIOD} if the pod that took it dies.
     */
    public LockService.Lock tryLockGuard() {
        return lockService.tryLock(BlobStorageUtil.toStoragePath(lockService.getPrefix(), "system-import"));
    }

    /**
     * Reads {@code archive} as the ZIP that {@link AdminSystemExportService} writes, validates it and stages every
     * blob. Throws {@link AdminSystemImportRejectedException} (400 for a bad archive, 408 for a stalled upload) with
     * the staging already removed; any other exception (e.g. blob storage failing to write) also leaves the staging
     * removed, for the caller to answer 500.
     */
    public StagedImport stage(InputStream archive) {
        String timestamp = LocalDateTime.now().format(TempFolderCleaner.FOLDER_FORMATTER);
        String stagingPrefix = STAGING_FOLDER + ResourceDescriptor.PATH_SEPARATOR + timestamp
                + ResourceDescriptor.PATH_SEPARATOR + idGenerator.get() + ResourceDescriptor.PATH_SEPARATOR;
        BlobCalls uploads = newCalls();
        try {
            return stageArchive(archive, stagingPrefix, uploads);
        } catch (Throwable e) {
            uploads.awaitQuietly();
            try {
                deletePrefix(stagingPrefix);
            } catch (Throwable cleanupFailure) {
                log.warn("Failed to delete system import staging: {}", stagingPrefix, cleanupFailure);
            }
            throw translateStageFailure(e);
        }
    }

    /**
     * Deletes a system import's own staging prefix, whether it succeeded or was rejected. Never throws: a failure
     * is logged, and the 24-hour sweeper reclaims what is left.
     */
    public void deleteStaging(StagedImport staged) {
        try {
            deletePrefix(staged.stagingPrefix());
        } catch (Throwable e) {
            log.warn("Failed to delete system import staging: {}", staged.stagingPrefix(), e);
        }
    }

    /**
     * Runs the replace phase under {@link MergedConfigStore#ADMIN_BUCKET_LOCATIONS}, updating {@code progress} as it
     * goes. Logs a warning and keeps going if it runs longer than the lock period, and if the rebuild throws, rather
     * than abort with the blobs already replaced.
     */
    public ImportResult replace(StagedImport staged, Progress progress) {
        long requestedAt = clock.getAsLong();
        return lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> replaceUnderLocks(staged, progress, requestedAt));
    }

    private ImportResult replaceUnderLocks(StagedImport staged, Progress progress, long requestedAt) {
        long startedAt = clock.getAsLong();
        List<String> warnings = new ArrayList<>();

        progress.setPhase(Progress.Phase.WIPE);
        long deleted = wipeBuckets(staged.bucketLocations(), progress);
        long wipeEndedAt = clock.getAsLong();

        progress.setPhase(Progress.Phase.COPY);
        progress.setTotal(staged.blobCount());
        long copied = promoteStagedBlobs(staged.stagingPrefix(), progress);
        long copyEndedAt = clock.getAsLong();

        progress.setPhase(Progress.Phase.INVALIDATE);
        resourceService.invalidate(staged.bucketLocations());
        invalidateEncryptionKeys(staged.bucketLocations());
        long invalidateEndedAt = clock.getAsLong();

        progress.setPhase(Progress.Phase.REBUILD);
        rebuildMergedConfig(warnings);
        long endedAt = clock.getAsLong();

        long totalMillis = endedAt - startedAt;
        log.info("System import replace finished: buckets={}, lockWait={}ms, wipe={}ms ({} blobs), copy={}ms ({} blobs), invalidate={}ms, "
                        + "rebuild={}ms, total={}ms", staged.bucketLocations(), startedAt - requestedAt, wipeEndedAt - startedAt, deleted,
                copyEndedAt - wipeEndedAt, copied, invalidateEndedAt - copyEndedAt, endedAt - invalidateEndedAt, totalMillis);

        long overMillis = totalMillis - LockService.LOCK_PERIOD.toMillis();
        if (overMillis > 0) {
            log.warn("System import replace exceeded the lock period of {}s by {}ms, so the bucket locks lapsed before it ended",
                    LockService.LOCK_PERIOD.toSeconds(), overMillis);
        }

        return new ImportResult(copied, deleted, warnings);
    }

    private void rebuildMergedConfig(List<String> warnings) {
        try {
            mergedConfigStore.rebuildNowUnderBucketLocks();
        } catch (Throwable e) {
            log.warn("Merged config rebuild failed after a system import; this pod's config may be stale until the next "
                    + "periodic rebuild", e);
            warnings.add("Merged config rebuild failed; this pod's config may be stale until the next periodic rebuild.");
        }
        try {
            mergedConfigStore.requestRebuildOnAllPods();
        } catch (Throwable e) {
            log.warn("Failed to tell the other pods to rebuild the merged config after a system import; they rebuild on their "
                    + "own schedule", e);
            warnings.add("The other pods were not told to rebuild; they rebuild the merged config on their own schedule.");
        }
    }

    private long wipeBuckets(List<String> bucketLocations, Progress progress) {
        BlobCalls deletes = newCalls();
        long deleted = 0;
        for (String bucketLocation : bucketLocations) {
            String marker = null;
            do {
                PageSet<? extends StorageMetadata> page = blobStorage.list(bucketLocation, marker, PAGE_SIZE, true);
                for (StorageMetadata meta : page) {
                    if (meta.getType() == StorageType.BLOB && !isUnderEncryptionKeys(bucketLocation, meta.getName())) {
                        String path = meta.getName();
                        deletes.submit(() -> {
                            blobStorage.delete(path);
                            progress.addDeleted();
                        });
                        deleted++;
                    }
                }
                marker = page.getNextMarker();
            } while (marker != null);
        }
        deletes.awaitAll();
        return deleted;
    }

    private static boolean isUnderEncryptionKeys(String bucketLocation, String path) {
        return path.startsWith(bucketLocation + ResourceTypes.ENCRYPTION_KEYS.group() + ResourceDescriptor.PATH_SEPARATOR);
    }

    private void invalidateEncryptionKeys(List<String> bucketLocations) {
        for (String bucketLocation : bucketLocations) {
            String bucketName = Strings.CS.removeEnd(bucketLocation, ResourceDescriptor.PATH_SEPARATOR);
            contentEncryptionKeyService.invalidateKey(new BucketInfo(bucketName, bucketLocation));
        }
    }

    private long promoteStagedBlobs(String stagingPrefix, Progress progress) {
        BlobCalls copies = newCalls();
        long copied = 0;
        String marker = null;
        do {
            PageSet<? extends StorageMetadata> page = blobStorage.list(stagingPrefix, marker, PAGE_SIZE, true);
            for (StorageMetadata meta : page) {
                if (meta.getType() == StorageType.BLOB) {
                    String stagedPath = meta.getName();
                    String realPath = stagedPath.substring(stagingPrefix.length());
                    copies.submit(() -> {
                        blobStorage.copy(stagedPath, realPath, null);
                        progress.addCopied();
                    });
                    copied++;
                }
            }
            marker = page.getNextMarker();
        } while (marker != null);
        copies.awaitAll();
        return copied;
    }

    private void deletePrefix(String prefix) {
        BlobCalls deletes = newCalls();
        String marker = null;
        do {
            PageSet<? extends StorageMetadata> page = blobStorage.list(prefix, marker, PAGE_SIZE, true);
            for (StorageMetadata meta : page) {
                if (meta.getType() == StorageType.BLOB) {
                    String path = meta.getName();
                    deletes.submit(() -> {
                        try {
                            blobStorage.delete(path);
                        } catch (Throwable e) {
                            log.warn("Failed to delete staged blob: {}", path, e);
                        }
                    });
                }
            }
            marker = page.getNextMarker();
        } while (marker != null);
        deletes.awaitAll();
    }

    private BlobCalls newCalls() {
        return new BlobCalls(executor, settings.getParallelism());
    }

    private StagedImport stageArchive(InputStream archive, String stagingPrefix, BlobCalls uploads) throws IOException {
        long startedAt = clock.getAsLong();
        TimedInputStream timed = new TimedInputStream(archive);
        ZipInputStream zip = new ZipInputStream(timed, StandardCharsets.UTF_8);
        ZipEntry first = zip.getNextEntry();
        validationService.validateManifestEntry(first);
        validationService.validateManifest(metadataReader.readManifest(zip));

        Set<Long> seenNames = new HashSet<>();
        seenNames.add(hash64(first.getName()));

        String pendingMetadataName = null;
        String pendingBlobPath = null;
        BlobAttributes pendingMetadata = null;
        Set<String> buckets = new HashSet<>();
        long blobCount = 0;

        for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
            String name = entry.getName();
            if (!seenNames.add(hash64(name))) {
                throw rejected("Duplicate entry: " + name);
            }

            if (name.startsWith(SystemArchiveBlobMetadata.ENTRY_PREFIX)) {
                if (pendingMetadata != null) {
                    throw rejected("Metadata entry without its blob: " + pendingMetadataName);
                }
                String blobPath = name.substring(SystemArchiveBlobMetadata.ENTRY_PREFIX.length());
                validationService.validateBlobName(blobPath);
                pendingMetadataName = name;
                pendingBlobPath = blobPath;
                pendingMetadata = metadataReader.readMetadata(zip);
                continue;
            }

            if (name.startsWith(".dial-system-export" + ResourceDescriptor.PATH_SEPARATOR)) {
                throw rejected("Unexpected archive metadata entry: " + name);
            }

            if (pendingMetadata == null || !name.equals(pendingBlobPath)) {
                throw rejected("Blob entry without its metadata: " + name);
            }
            validationService.validateBlobName(name);

            uploadStagedBlob(name, stagingPrefix + name, pendingMetadata, zip, uploads);
            buckets.add(name.substring(0, name.indexOf(ResourceDescriptor.PATH_SEPARATOR) + 1));
            blobCount++;
            pendingMetadata = null;
            pendingMetadataName = null;
            pendingBlobPath = null;
        }

        if (pendingMetadata != null) {
            throw rejected("Metadata entry without its blob: " + pendingMetadataName);
        }
        if (blobCount == 0) {
            throw rejected("Archive has no blob entries");
        }
        uploads.awaitAll();
        log.info("System import stage finished: blobs={}, bytes={}, duration={}ms, waitingForClient={}ms",
                blobCount, timed.bytes, clock.getAsLong() - startedAt, timed.waitNanos / NANOS_PER_MILLI);
        List<String> bucketLocations = MergedConfigStore.ADMIN_BUCKET_LOCATIONS.stream().filter(buckets::contains).toList();
        return new StagedImport(stagingPrefix, blobCount, bucketLocations);
    }

    private void uploadStagedBlob(String name, String path, BlobAttributes attributes, ZipInputStream zip, BlobCalls uploads)
            throws IOException {
        byte[] head = zip.readNBytes(MAX_BUFFERED_BLOB_BYTES + 1);
        if (validationService.isEncryptionKey(name)) {
            validationService.validateEncryptionKey(name, attributes, head);
        }
        if (head.length <= MAX_BUFFERED_BLOB_BYTES) {
            uploads.submit(() -> blobStorage.upload(path, attributes, new ByteArrayInputStream(head)));
        } else {
            uploads.runInline(() -> blobStorage.upload(path, attributes,
                    new SequenceInputStream(new ByteArrayInputStream(head), new UnclosableInputStream(zip))));
        }
        zip.closeEntry();
    }

    private static long hash64(String name) {
        // FNV-1a 64-bit: duplicate names are tracked by a hash of the name, not the name itself, to bound memory
        long hash = 0xcbf29ce484222325L;
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            hash ^= (b & 0xffL);
            hash *= 0x100000001b3L;
        }
        return hash;
    }

    private static AdminSystemImportRejectedException rejected(String message) {
        return new AdminSystemImportRejectedException(HttpStatus.BAD_REQUEST, message);
    }

    private static RuntimeException translateStageFailure(Throwable e) {
        if (e instanceof AdminSystemImportRejectedException rejected) {
            return rejected;
        }
        if (e instanceof InputStreamAdapter.StallTimeoutException stall) {
            return new AdminSystemImportRejectedException(HttpStatus.REQUEST_TIMEOUT, stall.getMessage());
        }
        if (e instanceof IOException io) {
            return new AdminSystemImportRejectedException(HttpStatus.BAD_REQUEST, "Invalid archive: " + io.getMessage());
        }
        if (e instanceof RuntimeException re) {
            return re;
        }
        return new RuntimeException(e);
    }

    public record StagedImport(String stagingPrefix, long blobCount, List<String> bucketLocations) {
    }

    public record ImportResult(long importedBlobs, long deletedBlobs, List<String> warnings) {
    }

    /**
     * Shared, mutable progress of a running replace phase: the controller's periodic timer reads it on the event
     * loop while {@link #replace} updates it, the counters from the workers that run its blob calls.
     */
    public static final class Progress {

        public enum Phase {
            WIPE, COPY, INVALIDATE, REBUILD
        }

        private volatile Phase phase = Phase.WIPE;
        private final AtomicLong deleted = new AtomicLong();
        private final AtomicLong copied = new AtomicLong();
        private volatile long total;

        public Phase getPhase() {
            return phase;
        }

        public long getDeleted() {
            return deleted.get();
        }

        public long getCopied() {
            return copied.get();
        }

        public long getTotal() {
            return total;
        }

        void setPhase(Phase phase) {
            this.phase = phase;
        }

        void addDeleted() {
            deleted.incrementAndGet();
        }

        void addCopied() {
            copied.incrementAndGet();
        }

        void setTotal(long total) {
            this.total = total;
        }
    }

    private static final class TimedInputStream extends FilterInputStream {
        private long bytes;
        private long waitNanos;

        private TimedInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            long startedAt = System.nanoTime();
            try {
                int value = in.read();
                if (value >= 0) {
                    bytes++;
                }
                return value;
            } finally {
                waitNanos += System.nanoTime() - startedAt;
            }
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            long startedAt = System.nanoTime();
            try {
                int read = in.read(buffer, offset, length);
                if (read > 0) {
                    bytes += read;
                }
                return read;
            } finally {
                waitNanos += System.nanoTime() - startedAt;
            }
        }
    }

    private static final class UnclosableInputStream extends FilterInputStream {
        private UnclosableInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
        }
    }

    @FunctionalInterface
    private interface BlobCall {
        void run() throws IOException;
    }

    /**
     * Runs blob calls on the executor, at most {@code limit} at a time, counting those {@link #runInline} runs on the
     * caller's thread. After the first failure no further call starts, and {@link #submit} and {@link #awaitAll} throw it
     * only once every call in flight has ended.
     */
    private static final class BlobCalls {
        private final Executor executor;
        private final int limit;
        private final Semaphore permits;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        private BlobCalls(Executor executor, int limit) {
            this.executor = executor;
            this.limit = limit;
            this.permits = new Semaphore(limit);
        }

        void submit(BlobCall call) {
            permits.acquireUninterruptibly();
            if (failure.get() != null) {
                permits.release();
                awaitAll();
            }
            try {
                executor.execute(() -> {
                    try {
                        call.run();
                    } catch (Throwable e) {
                        failure.compareAndSet(null, e);
                    } finally {
                        permits.release();
                    }
                });
            } catch (RuntimeException e) {
                permits.release();
                failure.compareAndSet(null, e);
                awaitAll();
            }
        }

        void runInline(BlobCall call) throws IOException {
            permits.acquireUninterruptibly();
            if (failure.get() != null) {
                permits.release();
                awaitAll();
            }
            try {
                call.run();
            } finally {
                permits.release();
            }
        }

        void awaitAll() {
            awaitQuietly();
            Throwable error = failure.get();
            if (error instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (error instanceof Error fatal) {
                throw fatal;
            }
            if (error != null) {
                throw new RuntimeException(error.getMessage(), error);
            }
        }

        void awaitQuietly() {
            permits.acquireUninterruptibly(limit);
            permits.release(limit);
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /**
         * Milliseconds the stage phase waits for the next chunk of the upload before failing it as stalled.
         */
        private long stallTimeout = 60_000;
        /**
         * Milliseconds between progress lines while the replace phase runs.
         */
        private long progressInterval = 15_000;
        /**
         * Blob storage calls a system import runs at a time.
         */
        private int parallelism = 4;

        public void validate() {
            if (stallTimeout <= 0) {
                throw new IllegalArgumentException("adminSystemImport.stallTimeout must be positive, but got: " + stallTimeout);
            }
            if (progressInterval <= 0) {
                throw new IllegalArgumentException("adminSystemImport.progressInterval must be positive, but got: " + progressInterval);
            }
            if (parallelism < 1) {
                throw new IllegalArgumentException("adminSystemImport.parallelism must be at least 1, but got: " + parallelism);
            }
        }
    }
}
