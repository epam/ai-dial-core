package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;
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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Streams the selected admin buckets ({@code public}, {@code platform}) as a ZIP archive.
 *
 * <p>Layout: {@code .dial-system-export/manifest.json} first; then, for every blob, {@code .dial-system-export/metadata/<path>}
 * (content headers, blob timestamps and user metadata) immediately followed by the entry {@code <path>} holding the
 * bytes exactly as stored. Credentials are not exported. Encryption keys are exported only when a KMS provider is
 * configured, because without one they are stored in plaintext.
 *
 * <p>The archive is read from blob storage, so a resource written within the sync delay of the resource service
 * may not be in it yet.
 */
@Slf4j
public class AdminSystemExportService {

    static final String MANIFEST_ENTRY = ".dial-system-export/manifest.json";
    static final String METADATA_ENTRY_PREFIX = ".dial-system-export/metadata/";
    static final int FORMAT_VERSION = 1;

    private static final String IDENTITY_ENCODING = "identity";
    private static final int PAGE_SIZE = 1000;
    private static final String CREDENTIALS_GROUP = ResourceTypes.CREDENTIALS.group() + "/";
    private static final String ENCRYPTION_KEYS_GROUP = ResourceTypes.ENCRYPTION_KEYS.group() + "/";

    private final BlobStorage blobStorage;
    private final LockService lockService;
    private final LongSupplier clock;
    private final String coreVersion;
    private final List<String> excludedGroups;

    public AdminSystemExportService(BlobStorage blobStorage, LockService lockService, LongSupplier clock, String coreVersion,
                                    boolean kmsEnabled) {
        this.blobStorage = blobStorage;
        this.lockService = lockService;
        this.clock = clock;
        this.coreVersion = coreVersion;
        this.excludedGroups = kmsEnabled ? List.of(CREDENTIALS_GROUP) : List.of(CREDENTIALS_GROUP, ENCRYPTION_KEYS_GROUP);
    }

    /**
     * Resolves the {@code scope} request parameter to bucket locations, in the order of
     * {@link MergedConfigStore#ADMIN_BUCKET_LOCATIONS}. A scope name is a bucket location without the trailing separator.
     *
     * @param scope comma-separated bucket names, compared case-insensitively; {@code null} selects every bucket
     * @throws IllegalArgumentException if the scope is empty, has an empty element or names an unknown bucket
     */
    public static List<String> resolveBucketLocations(String scope) {
        if (scope == null) {
            return MergedConfigStore.ADMIN_BUCKET_LOCATIONS;
        }

        List<String> validNames = MergedConfigStore.ADMIN_BUCKET_LOCATIONS.stream().map(AdminSystemExportService::toScopeName).toList();
        Set<String> requestedNames = new HashSet<>();
        for (String name : scope.split(",", -1)) {
            String normalized = name.trim().toLowerCase(Locale.ROOT);
            if (!validNames.contains(normalized)) {
                throw new IllegalArgumentException("scope must be a comma-separated list of: " + String.join(", ", validNames));
            }
            requestedNames.add(normalized);
        }
        return MergedConfigStore.ADMIN_BUCKET_LOCATIONS.stream().filter(location -> requestedNames.contains(toScopeName(location))).toList();
    }

    private static String toScopeName(String bucketLocation) {
        return Strings.CS.removeEnd(bucketLocation, ResourceDescriptor.PATH_SEPARATOR);
    }

    /**
     * Blocks while holding both bucket locks. Does not close {@code out}. If this throws, the archive is
     * left unfinished, so the consumer must treat what it has received as truncated.
     *
     * <p>The locks are not renewed, so the system export fails once it has run for the lock period.
     *
     * @param start the time recorded in the manifest as the start of the system export
     * @param bucketLocations the buckets to lock and export, see {@link #resolveBucketLocations}
     * @throws AdminSystemExportBusyException if a bucket lock is held by another system export or a writer, nothing is written then
     */
    public void export(OutputStream out, Instant start, List<String> bucketLocations) {
        long startMillis = clock.getAsLong();
        LockService.Lock locks = lockService.tryLockBuckets(bucketLocations);
        if (locks == null) {
            log.info("Admin system export rejected: a bucket lock is held by another system export or a write");
            throw new AdminSystemExportBusyException("A bucket lock is held by another system export or a write");
        }

        ExportStats stats = new ExportStats();
        try (locks) {
            writeArchive(out, start, startMillis, bucketLocations, stats);
        } catch (Throwable e) {
            Duration duration = Duration.ofMillis(clock.getAsLong() - startMillis);
            log.error("Admin system export failed: duration={}ms, entries={}, bytes={}", duration.toMillis(), stats.entries, stats.bytes, e);
            throw e;
        }

        Duration duration = Duration.ofMillis(clock.getAsLong() - startMillis);
        log.info("Admin system export completed: duration={}ms, entries={}, bytes={}", duration.toMillis(), stats.entries, stats.bytes);
    }

    private void writeArchive(OutputStream out, Instant start, long startMillis, List<String> bucketLocations, ExportStats stats) {
        long deadlineMillis = startMillis + LockService.LOCK_PERIOD.toMillis();
        DiscardableZipOutputStream zip = new DiscardableZipOutputStream(new ExportOutputStream(out, stats, clock, deadlineMillis));
        try {
            writeJsonEntry(zip, MANIFEST_ENTRY, new Manifest(FORMAT_VERSION, coreVersion, start.truncatedTo(ChronoUnit.SECONDS).toString()), stats);

            for (String bucketLocation : bucketLocations) {
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

    private boolean shouldExport(String bucketLocation, String path) {
        return excludedGroups.stream().noneMatch(group -> path.startsWith(bucketLocation + group));
    }

    private void exportBlob(ZipOutputStream zip, String path, ExportStats stats) throws IOException {
        Blob blob = blobStorage.loadWithoutTranscoding(path);
        if (blob == null) {
            log.debug("Skipping blob deleted during the system export: {}", path);
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

    private static final class ExportStats {
        private long entries;
        private long bytes;
    }

    /**
     * A failed system export must not write the central directory, so the stream is never closed on failure,
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

    /**
     * Counts the bytes and refuses to write once the bucket locks may have expired, so that the archive is
     * never completed from blobs that were read without them.
     */
    private static final class ExportOutputStream extends OutputStream {
        private final OutputStream delegate;
        private final ExportStats stats;
        private final LongSupplier clock;
        private final long deadlineMillis;

        private ExportOutputStream(OutputStream delegate, ExportStats stats, LongSupplier clock, long deadlineMillis) {
            this.delegate = delegate;
            this.stats = stats;
            this.clock = clock;
            this.deadlineMillis = deadlineMillis;
        }

        @Override
        public void write(int b) throws IOException {
            checkDeadline();
            delegate.write(b);
            stats.bytes++;
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            checkDeadline();
            delegate.write(b, off, len);
            stats.bytes += len;
        }

        private void checkDeadline() throws IOException {
            if (clock.getAsLong() > deadlineMillis) {
                throw new IOException("The system export ran longer than the lock period of " + LockService.LOCK_PERIOD.toSeconds()
                        + "s, so the buckets may have been modified");
            }
        }

        @Override
        public void flush() throws IOException {
            delegate.flush();
        }
    }

    private record Manifest(int formatVersion, String coreVersion, String createdAt) {
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Settings {
        /**
         * Milliseconds the system export waits for a slow client to take the buffered data before it fails.
         */
        private long stallTimeout = 60_000;

        public void validate() {
            if (stallTimeout <= 0 || stallTimeout >= LockService.LOCK_PERIOD.toMillis()) {
                throw new IllegalArgumentException("adminSystemExport.stallTimeout must be positive and below the lock period of "
                        + LockService.LOCK_PERIOD.toMillis() + " ms, but got: " + stallTimeout);
            }
        }
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BlobMetadata(String contentType, String contentEncoding, String contentDisposition, String contentLanguage,
                                String cacheControl, String creationDate, String lastModified, Map<String, String> userMetadata) {
    }
}
