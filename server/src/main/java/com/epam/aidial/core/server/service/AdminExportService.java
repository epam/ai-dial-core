package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.MutableBlobMetadata;
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
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams the {@code public} and {@code platform} buckets as a ZIP archive.
 *
 * <p>Layout: {@code .dial-export/manifest.json} first; then, for every blob, {@code .dial-export/metadata/<path>}
 * (content headers, blob timestamps and user metadata) immediately followed by the entry {@code <path>} holding the
 * bytes exactly as stored. Encryption keys and credentials are not exported.
 *
 * <p>The archive is read from blob storage, so a resource written within the sync delay of the resource service
 * may not be in it yet.
 */
@Slf4j
public class AdminExportService {

    static final String MANIFEST_ENTRY = ".dial-export/manifest.json";
    static final String METADATA_ENTRY_PREFIX = ".dial-export/metadata/";
    static final int FORMAT_VERSION = 1;

    private static final String IDENTITY_ENCODING = "identity";
    private static final int PAGE_SIZE = 1000;
    private static final List<String> EXCLUDED_GROUPS = List.of(
            ResourceTypes.ENCRYPTION_KEYS.group() + "/",
            ResourceTypes.CREDENTIALS.group() + "/");

    private final BlobStorage blobStorage;
    private final LockService lockService;
    private final Clock clock;
    private final String coreVersion;

    public AdminExportService(BlobStorage blobStorage, LockService lockService, Clock clock, String coreVersion) {
        this.blobStorage = blobStorage;
        this.lockService = lockService;
        this.clock = clock;
        this.coreVersion = coreVersion;
    }

    /**
     * Blocks while holding both bucket locks. Does not close {@code out}. If this throws, the archive is
     * left unfinished, so the consumer must treat what it has received as truncated.
     *
     * @throws AdminExportBusyException if a bucket lock is held by another export or a writer, nothing is written then
     */
    public void export(OutputStream out) {
        LockService.Lock locks = lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
        if (locks == null) {
            log.info("Admin export rejected: a bucket lock is held by another export or a write");
            throw new AdminExportBusyException("A bucket lock is held by another export or a write");
        }

        Instant start = clock.instant();
        ExportStats stats = new ExportStats();
        try (locks) {
            writeArchive(out, start, stats);
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
        DiscardableZipOutputStream zip = new DiscardableZipOutputStream(new CountingOutputStream(out, stats));
        try {
            writeJsonEntry(zip, MANIFEST_ENTRY, new Manifest(FORMAT_VERSION, coreVersion, start.truncatedTo(ChronoUnit.SECONDS).toString()), stats);

            for (String bucketLocation : MergedConfigStore.ADMIN_BUCKET_LOCATIONS) {
                exportBucket(zip, bucketLocation, stats);
            }

            zip.close();
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            zip.discard();
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
        return EXCLUDED_GROUPS.stream().noneMatch(group -> path.startsWith(bucketLocation + group));
    }

    private void exportBlob(ZipOutputStream zip, String path, ExportStats stats) throws IOException {
        Blob blob = blobStorage.load(path);
        if (blob == null) {
            log.debug("Skipping blob deleted during the export: {}", path);
            return;
        }

        MutableBlobMetadata meta = blob.getMetadata();
        try (InputStream payload = blob.getPayload().openStream()) {
            writeJsonEntry(zip, METADATA_ENTRY_PREFIX + path, toBlobMetadata(meta), stats);
            writeBlobContent(zip, path, meta.getContentMetadata().getContentEncoding(), payload, stats);
        }
    }

    private static void writeBlobContent(ZipOutputStream zip, String path, String contentEncoding, InputStream payload, ExportStats stats)
            throws IOException {
        putEntry(zip, path, compressionLevel(contentEncoding), stats);
        payload.transferTo(zip);
        zip.closeEntry();
    }

    private static void writeJsonEntry(ZipOutputStream zip, String name, Object value, ExportStats stats) throws IOException {
        putEntry(zip, name, Deflater.DEFAULT_COMPRESSION, stats);
        zip.write(ProxyUtil.MAPPER.writeValueAsBytes(value));
        zip.closeEntry();
    }

    private static void putEntry(ZipOutputStream zip, String name, int compressionLevel, ExportStats stats) throws IOException {
        zip.setLevel(compressionLevel);
        zip.putNextEntry(new ZipEntry(name));
        stats.entries++;
    }

    private static int compressionLevel(String contentEncoding) {
        boolean encoded = !StringUtils.isBlank(contentEncoding) && !IDENTITY_ENCODING.equalsIgnoreCase(contentEncoding.trim());
        return encoded ? Deflater.NO_COMPRESSION : Deflater.DEFAULT_COMPRESSION;
    }

    private static BlobMetadata toBlobMetadata(MutableBlobMetadata meta) {
        ContentMetadata content = meta.getContentMetadata();
        return new BlobMetadata(
                content.getContentType(),
                content.getContentEncoding(),
                content.getContentDisposition(),
                content.getContentLanguage(),
                content.getCacheControl(),
                toIsoString(meta.getCreationDate()),
                toIsoString(meta.getLastModified()),
                meta.getUserMetadata());
    }

    private static String toIsoString(Date date) {
        return date == null ? null : date.toInstant().toString();
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

    /**
     * A failed export must not write the central directory, so the stream is never closed on failure,
     * and its native deflater is released by {@link #discard()} instead.
     */
    static final class DiscardableZipOutputStream extends ZipOutputStream {

        DiscardableZipOutputStream(OutputStream out) {
            super(out);
        }

        /**
         * Releases the deflater without finishing the archive. Safe to call after {@link #close()}.
         */
        void discard() {
            def.end();
        }
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
    private record BlobMetadata(String contentType, String contentEncoding, String contentDisposition, String contentLanguage,
                                String cacheControl, String creationDate, String lastModified, Map<String, String> userMetadata) {
    }
}
