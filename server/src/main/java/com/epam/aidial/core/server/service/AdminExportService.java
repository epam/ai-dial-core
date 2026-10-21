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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.ReentrantLock;
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
    private static final int READ_BUFFER_SIZE = 64 * 1024;
    private static final List<String> EXCLUDED_GROUPS = List.of(
            ResourceTypes.ENCRYPTION_KEYS.group() + "/",
            ResourceTypes.CREDENTIALS.group() + "/");

    private final BlobStorage blobStorage;
    private final LockService lockService;
    private final Clock clock;
    private final String coreVersion;
    private final Executor releaseExecutor;

    /**
     * @param releaseExecutor runs the release of the locks of a closed export, because closing may happen on an event loop
     */
    public AdminExportService(BlobStorage blobStorage, LockService lockService, Clock clock, String coreVersion, Executor releaseExecutor) {
        this.blobStorage = blobStorage;
        this.lockService = lockService;
        this.clock = clock;
        this.coreVersion = coreVersion;
        this.releaseExecutor = releaseExecutor;
    }

    /**
     * Takes both bucket locks and returns the archive as a stream that builds it while it is read, so a consumer that
     * reads slowly slows the export down instead of buffering it. The locks are held until the archive is complete,
     * a read fails, or the stream is closed. If a read fails, the archive is left unfinished, so the consumer must
     * treat what it has received as truncated.
     *
     * @throws AdminExportBusyException if a bucket lock is held by another export or a writer, nothing is read then
     */
    public ExportStream openExport() {
        LockService.Lock locks = lockService.tryLockBuckets(MergedConfigStore.ADMIN_BUCKET_LOCATIONS);
        if (locks == null) {
            log.info("Admin export rejected: a bucket lock is held by another export or a write");
            throw new AdminExportBusyException("A bucket lock is held by another export or a write");
        }

        return new ExportStream(locks, clock.instant());
    }

    private static boolean shouldExport(String bucketLocation, String path) {
        return EXCLUDED_GROUPS.stream().noneMatch(group -> path.startsWith(bucketLocation + group));
    }

    private static Entry jsonEntry(String name, Object value) throws IOException {
        return new Entry(name, Deflater.DEFAULT_COMPRESSION, ProxyUtil.MAPPER.writeValueAsBytes(value), null);
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

    /**
     * The archive, built one small step per {@link #read} call: a step writes at most one chunk of a blob into the
     * ZIP stream, which deflates it into an in-memory sink that the read drains. Nothing is built ahead of the reader,
     * so the memory held is a chunk and its deflated output, plus the central directory at the very end.
     *
     * <p>Reads block and must not run on an event loop. {@link #close()} may be called from any thread
     * at any time, including during a read, and never blocks.
     */
    public final class ExportStream extends InputStream {

        private final ReentrantLock lock = new ReentrantLock();
        private final LockService.Lock bucketLocks;
        private final Instant start;
        private final ExportStats stats = new ExportStats();
        private final Sink sink = new Sink();
        private final DiscardableZipOutputStream zip = new DiscardableZipOutputStream(new CountingOutputStream(sink, stats));
        private final Iterator<String> bucketLocations = MergedConfigStore.ADMIN_BUCKET_LOCATIONS.iterator();
        private final Deque<Entry> pending = new ArrayDeque<>();
        private final byte[] chunk = new byte[READ_BUFFER_SIZE];

        // guarded by the lock
        private String bucketLocation;
        private String marker;
        private Iterator<? extends StorageMetadata> page = Collections.emptyIterator();
        private InputStream payload;
        private boolean started;
        private boolean finished;
        private Throwable failure;

        private volatile boolean closed;
        private volatile boolean released;
        private volatile boolean reading;
        private volatile long lastActivityNanos = System.nanoTime();

        private ExportStream(LockService.Lock bucketLocks, Instant start) {
            this.bucketLocks = bucketLocks;
            this.start = start;
        }

        @Override
        public int read() throws IOException {
            byte[] single = new byte[1];
            return read(single, 0, 1) < 0 ? -1 : single[0] & 0xFF;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);

            if (length == 0) {
                return 0;
            }

            reading = true;
            lock.lock();
            try {
                if (failure != null) {
                    throw new IOException("Export failed", failure);
                }

                if (closed) {
                    throw new IOException("Export is closed");
                }

                fillSink();
                return sink.isEmpty() ? -1 : sink.drain(bytes, offset, length);
            } finally {
                lock.unlock();
                lastActivityNanos = System.nanoTime();
                reading = false;
            }
        }

        /**
         * Releases the locks on a worker thread, after a read in progress ends. Does not finish the archive.
         */
        @Override
        public void close() {
            if (released) {
                return;
            }

            closed = true;
            releaseExecutor.execute(this::release);
        }

        /**
         * Whether the archive is unfinished and nobody has asked for bytes for longer than the timeout.
         * A read in progress, for example one waiting for a slow blob, is not idle.
         */
        public boolean isIdleLongerThan(Duration timeout) {
            return !reading && !released && System.nanoTime() - lastActivityNanos > timeout.toNanos();
        }

        // the lock must be held
        private void fillSink() throws IOException {
            try {
                while (sink.isEmpty() && !finished) {
                    if (closed) {
                        throw new IOException("Export is closed");
                    }

                    advance();
                }
            } catch (Throwable e) {
                failure = e;
                release();
                throw e;
            }

            if (finished) {
                release();
            }
        }

        // the lock must be held
        private void advance() throws IOException {
            if (payload != null) {
                int read = payload.read(chunk);
                if (read < 0) {
                    zip.closeEntry();
                    closePayload();
                } else {
                    zip.write(chunk, 0, read);
                }
                return;
            }

            if (!fillPending()) {
                zip.close();
                finished = true;
                return;
            }

            Entry entry = pending.poll();
            zip.setLevel(entry.compressionLevel());
            zip.putNextEntry(new ZipEntry(entry.name()));
            stats.entries++;

            if (entry.blob() == null) {
                zip.write(entry.json());
                zip.closeEntry();
            } else {
                payload = entry.blob().getPayload().openStream();
            }
        }

        // the lock must be held, returns false when there is nothing left to export
        private boolean fillPending() throws IOException {
            while (pending.isEmpty()) {
                if (!started) {
                    started = true;
                    pending.add(jsonEntry(MANIFEST_ENTRY, new Manifest(FORMAT_VERSION, coreVersion, start.truncatedTo(ChronoUnit.SECONDS).toString())));
                    continue;
                }

                String path = nextBlobPath();
                if (path == null) {
                    return false;
                }

                Blob blob = blobStorage.load(path);
                if (blob == null) {
                    log.debug("Skipping blob deleted during the export: {}", path);
                    continue;
                }

                MutableBlobMetadata meta = blob.getMetadata();
                pending.add(jsonEntry(METADATA_ENTRY_PREFIX + path, toBlobMetadata(meta)));
                pending.add(new Entry(path, compressionLevel(meta.getContentMetadata().getContentEncoding()), null, blob));
            }

            return true;
        }

        // the lock must be held, returns null when both buckets are listed
        private String nextBlobPath() {
            while (true) {
                while (page.hasNext()) {
                    StorageMetadata meta = page.next();
                    if (meta.getType() == StorageType.BLOB && shouldExport(bucketLocation, meta.getName())) {
                        return meta.getName();
                    }
                }

                if (marker == null) {
                    if (!bucketLocations.hasNext()) {
                        return null;
                    }
                    bucketLocation = bucketLocations.next();
                }

                PageSet<? extends StorageMetadata> blobs = blobStorage.list(bucketLocation, marker, PAGE_SIZE, true);
                page = blobs.iterator();
                marker = blobs.getNextMarker();
            }
        }

        // the lock must be held
        private void closePayload() throws IOException {
            if (payload != null) {
                InputStream stream = payload;
                payload = null;
                stream.close();
            }
        }

        private void release() {
            lock.lock();
            try {
                if (released) {
                    return;
                }

                released = true;
                try {
                    closePayload();
                } catch (IOException e) {
                    log.warn("Failed to close the blob content", e);
                } finally {
                    zip.discard();
                    bucketLocks.close();
                }

                logOutcome();
            } finally {
                lock.unlock();
            }
        }

        // the lock must be held
        private void logOutcome() {
            Duration duration = Duration.between(start, clock.instant());
            if (finished) {
                log.info("Admin export completed: duration={}ms, entries={}, bytes={}", duration.toMillis(), stats.entries, stats.bytes);
            } else if (closed) {
                log.warn("Admin export aborted: duration={}ms, entries={}, bytes={}", duration.toMillis(), stats.entries, stats.bytes);
            } else {
                log.error("Admin export failed: duration={}ms, entries={}, bytes={}", duration.toMillis(), stats.entries, stats.bytes, failure);
            }
            warnIfLockPeriodExceeded(duration);
        }
    }

    private static final class ExportStats {
        private long entries;
        private long bytes;
    }

    /**
     * Holds what the ZIP stream has written and the reader has not taken yet.
     */
    private static final class Sink extends ByteArrayOutputStream {
        private int position;

        boolean isEmpty() {
            return position == count;
        }

        int drain(byte[] bytes, int offset, int length) {
            int size = Math.min(length, count - position);
            System.arraycopy(buf, position, bytes, offset, size);
            position += size;

            if (position == count) {
                reset();
                position = 0;
            }

            return size;
        }
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

    /**
     * Either {@code json}, or the {@code blob} whose content is the entry.
     */
    private record Entry(String name, int compressionLevel, byte[] json, Blob blob) {
    }

    private record Manifest(int formatVersion, String coreVersion, String createdAt) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private record BlobMetadata(String contentType, String contentEncoding, String contentDisposition, String contentLanguage,
                                String cacheControl, String creationDate, String lastModified, Map<String, String> userMetadata) {
    }
}
