package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.io.ContentMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams the {@code public} and {@code platform} buckets as a ZIP archive.
 *
 * <p>Layout: {@code .dial-export/manifest.json} first; then, for every blob, {@code .dial-export/metadata/<path>}
 * (content type, content encoding, user metadata) immediately followed by the entry {@code <path>} holding the
 * bytes exactly as stored. Encryption keys and credentials are not exported.
 */
@Slf4j
public class AdminExportService {

    static final String MANIFEST_ENTRY = ".dial-export/manifest.json";
    static final String METADATA_ENTRY_PREFIX = ".dial-export/metadata/";
    static final int FORMAT_VERSION = 1;

    private static final int PAGE_SIZE = 1000;
    private static final int COPY_BUFFER_SIZE = 8192;
    private static final List<String> EXCLUDED_GROUPS = List.of(
            ResourceTypes.ENCRYPTION_KEYS.group() + "/",
            ResourceTypes.CREDENTIALS.group() + "/");

    private final BlobStorage blobStorage;
    private final ResourceService resourceService;
    private final LockService lockService;
    private final Clock clock;
    private final String coreVersion;

    public AdminExportService(BlobStorage blobStorage, ResourceService resourceService, LockService lockService,
                              Clock clock, String coreVersion) {
        this.blobStorage = blobStorage;
        this.resourceService = resourceService;
        this.lockService = lockService;
        this.clock = clock;
        this.coreVersion = coreVersion;
    }

    /**
     * Blocks while holding both bucket locks. Does not close {@code out}. If this throws, the archive is
     * left unfinished, so the consumer must treat what it has received as truncated.
     */
    public void export(OutputStream out) {
        Instant start = clock.instant();
        ExportStats stats = new ExportStats();
        try {
            lockService.underBucketLocks(MergedConfigStore.ADMIN_BUCKET_LOCATIONS, () -> {
                resourceService.flushPending(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
                writeArchive(out, start, stats);
                return null;
            });
        } catch (Throwable e) {
            Duration duration = Duration.between(start, clock.instant());
            log.error("Admin export failed: duration={}ms, entries={}, bytes={}", duration.toMillis(), stats.entries, stats.bytes, e);
            warnIfLockPeriodExceeded(duration);
            throw e;
        }

        Duration duration = Duration.between(start, clock.instant());
        log.info("Admin export completed: duration={}ms, entries={}, bytes={}", duration.toMillis(), stats.entries, stats.bytes);
        warnIfLockPeriodExceeded(duration);
    }

    private void writeArchive(OutputStream out, Instant start, ExportStats stats) {
        try {
            ZipOutputStream zip = new ZipOutputStream(new CountingOutputStream(out, stats));
            writeJsonEntry(zip, MANIFEST_ENTRY, new Manifest(FORMAT_VERSION, coreVersion, start.truncatedTo(ChronoUnit.SECONDS).toString()), stats);

            for (String bucketLocation : MergedConfigStore.ADMIN_BUCKET_LOCATIONS) {
                exportBucket(zip, bucketLocation, stats);
            }

            zip.close();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void exportBucket(ZipOutputStream zip, String bucketLocation, ExportStats stats) throws IOException {
        String marker = null;
        do {
            PageSet<? extends StorageMetadata> page = blobStorage.list(bucketLocation, marker, PAGE_SIZE, true);
            for (StorageMetadata meta : page) {
                if (meta.getType() == StorageType.BLOB && shouldExport(bucketLocation, meta.getName())) {
                    exportBlob(zip, meta.getName(), stats);
                }
            }
            marker = page.getNextMarker();
        } while (marker != null);
    }

    private static boolean shouldExport(String bucketLocation, String path) {
        String relativePath = path.substring(bucketLocation.length());
        return EXCLUDED_GROUPS.stream().noneMatch(relativePath::startsWith);
    }

    private void exportBlob(ZipOutputStream zip, String path, ExportStats stats) throws IOException {
        Blob blob = blobStorage.load(path);
        if (blob == null) {
            log.debug("Skipping blob deleted during the export: {}", path);
            return;
        }

        ContentMetadata content = blob.getMetadata().getContentMetadata();
        try (InputStream payload = blob.getPayload().openStream()) {
            writeJsonEntry(zip, METADATA_ENTRY_PREFIX + path,
                    new BlobMetadata(content.getContentType(), content.getContentEncoding(), blob.getMetadata().getUserMetadata()), stats);

            zip.putNextEntry(new ZipEntry(path));
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            for (int read = payload.read(buffer); read >= 0; read = payload.read(buffer)) {
                zip.write(buffer, 0, read);
            }
        }
        zip.closeEntry();
        stats.entries++;
    }

    private static void writeJsonEntry(ZipOutputStream zip, String name, Object value, ExportStats stats) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(ProxyUtil.MAPPER.writeValueAsBytes(value));
        zip.closeEntry();
        stats.entries++;
    }

    private static void warnIfLockPeriodExceeded(Duration duration) {
        if (duration.compareTo(LockService.LOCK_PERIOD) > 0) {
            log.warn("Admin export took longer than the lock period {}s: duration={}s, the bucket locks may have expired",
                    LockService.LOCK_PERIOD.toSeconds(), duration.toSeconds());
        }
    }

    private static final class ExportStats {
        private long entries;
        private long bytes;
    }

    private static final class CountingOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ExportStats stats;

        private CountingOutputStream(OutputStream delegate, ExportStats stats) {
            this.delegate = delegate;
            this.stats = stats;
        }

        @Override
        public void write(int b) throws IOException {
            delegate.write(b);
            stats.bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            delegate.write(b, off, len);
            stats.bytes += len;
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    private record Manifest(int formatVersion, String coreVersion, String createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BlobMetadata(String contentType, String contentEncoding, Map<String, String> userMetadata) {
    }
}
