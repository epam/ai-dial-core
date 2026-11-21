package com.epam.aidial.core.server.service;

import com.epam.aidial.core.credentials.data.credentials.BucketInfo;
import com.epam.aidial.core.credentials.encryption.ContentEncryptionKeyService;
import com.epam.aidial.core.credentials.exception.CekEncryptionException;
import com.epam.aidial.core.credentials.keymanagement.KeyManagementService;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.service.AdminSystemImportService.ImportMode;
import com.epam.aidial.core.server.util.CoreVersion;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.domain.internal.BlobBuilderImpl;
import org.jclouds.blobstore.domain.internal.MutableStorageMetadataImpl;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import redis.embedded.RedisServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AdminSystemImportService}. Blob storage is an in-memory fake (not the real filesystem
 * provider): the staging prefix embeds a {@code HH:mm:ss} timestamp, and colons are not valid in a Windows path,
 * so a real filesystem-backed store cannot be used to run this suite on every development machine.
 * {@link com.epam.aidial.core.storage.blobstore.BlobStorageUploadTest} and {@code TempFolderCleanerTest} already
 * cover the real filesystem provider for the operations this class relies on.
 */
class AdminSystemImportServiceTest {

    private static final String CORE_VERSION = "0.50.0";

    private RedisServerHandle redisServerHandle;
    private RedissonClient redis;
    private NavigableMap<String, StoredBlob> blobs;
    /**
     * Listing entries that are not blobs (folder markers in blob storage), keyed by full path. Empty for every test
     * but {@link #testFolderListingEntriesAreSkippedDuringWipeAndPromote}.
     */
    private NavigableSet<String> folderEntries;
    private CallProbe probe;
    private BlobStorage storage;
    private LockService lockService;
    private ResourceService resourceService;
    private MergedConfigStore mergedConfigStore;
    private KeyManagementService keyManagementService;
    private ContentEncryptionKeyService contentEncryptionKeyService;
    private AtomicLong clock;
    private AtomicLong idCounter;
    private AdminSystemImportService service;

    @BeforeEach
    void init() throws Exception {
        try {
            int redisPort = freePort();
            redisServerHandle = RedisServerHandle.start(redisPort);

            org.redisson.config.Config redissonConfig = new org.redisson.config.Config();
            redissonConfig.useSingleServer().setAddress("redis://localhost:" + redisPort);
            redis = Redisson.create(redissonConfig);

            blobs = new ConcurrentSkipListMap<>();
            folderEntries = new ConcurrentSkipListSet<>();
            probe = new CallProbe();
            storage = fakeBlobStorage(blobs, folderEntries, probe);

            lockService = new LockService(redis, null);
            TimerService timerService = fakeTimerService();
            ObjectMapper mapper = new ObjectMapper();
            String serviceConfig = """
                    {
                     "maxSize" : 67108864,
                     "maxSizeToCache": 1048576,
                     "syncPeriod": 60000,
                     "syncDelay": 60000,
                     "syncBatch": 4096,
                     "cacheExpiration": 300000,
                     "compressionMinSize": 256,
                     "heartbeatPeriod": 60000
                    }
                    """;
            ResourceService.Settings settings = mapper.readValue(serviceConfig, ResourceService.Settings.class);
            resourceService = new ResourceService(timerService, redis, storage, lockService, settings, null);

            mergedConfigStore = mock(MergedConfigStore.class);
            keyManagementService = mock(KeyManagementService.class);
            contentEncryptionKeyService = mock(ContentEncryptionKeyService.class);
            clock = new AtomicLong(1_700_000_000_000L);
            idCounter = new AtomicLong();
            service = newService();
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    private AdminSystemImportService newService() {
        return newService(new AdminSystemImportService.Settings(), false);
    }

    private AdminSystemImportService newService(AdminSystemImportService.Settings settings) {
        return newService(settings, false);
    }

    private AdminSystemImportService newService(AdminSystemImportService.Settings settings, boolean kmsEnabled) {
        return new AdminSystemImportService(storage, lockService, resourceService, mergedConfigStore,
                new AdminSystemImportValidationService(CoreVersion.parse(CORE_VERSION), kmsEnabled, keyManagementService),
                contentEncryptionKeyService, command -> Thread.ofVirtual().start(command), settings,
                clock::get, () -> "import-" + idCounter.incrementAndGet());
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @AfterEach
    void destroy() throws IOException {
        try {
            if (redis != null) {
                redis.shutdown();
            }
        } finally {
            if (redisServerHandle != null) {
                redisServerHandle.stop();
            }
        }
    }

    private static TimerService fakeTimerService() {
        TimerService timerService = mock(TimerService.class);
        when(timerService.scheduleWithFixedDelay(anyLong(), anyLong(), any())).thenReturn(mock(TimerService.Timer.class));
        return timerService;
    }

    /**
     * A plain in-memory stand-in for {@link BlobStorage}, covering only the operations {@link AdminSystemImportService}
     * uses: list, delete, copy, upload, load, exists. {@code folders} lists additionally as {@link StorageType#FOLDER}
     * entries (not real blobs, and not stored in {@code blobs}), the way blob storage lists a folder marker next to the
     * blobs under it. Delete, copy and upload go through {@code probe}; the maps must be safe for concurrent use.
     */
    private static BlobStorage fakeBlobStorage(NavigableMap<String, StoredBlob> blobs, NavigableSet<String> folders, CallProbe probe)
            throws IOException {
        BlobStorage storage = mock(BlobStorage.class);

        when(storage.list(anyString(), org.mockito.ArgumentMatchers.nullable(String.class), anyInt(), anyBoolean()))
                .thenAnswer(invocation -> {
                    String prefix = invocation.getArgument(0);
                    String marker = invocation.getArgument(1);
                    int maxResults = invocation.getArgument(2);
                    NavigableSet<String> keys = new TreeSet<>();
                    for (String key : blobs.navigableKeySet()) {
                        if (key.startsWith(prefix) && (marker == null || key.compareTo(marker) > 0)) {
                            keys.add(key);
                        }
                    }
                    for (String key : folders) {
                        if (key.startsWith(prefix) && (marker == null || key.compareTo(marker) > 0)) {
                            keys.add(key);
                        }
                    }
                    List<String> sortedKeys = new ArrayList<>(keys);
                    List<StorageMetadata> page = new ArrayList<>();
                    String nextMarker = null;
                    for (int i = 0; i < sortedKeys.size(); i++) {
                        if (i >= maxResults) {
                            nextMarker = sortedKeys.get(maxResults - 1);
                            break;
                        }
                        String key = sortedKeys.get(i);
                        page.add(storageMetadata(key, folders.contains(key) ? StorageType.FOLDER : StorageType.BLOB));
                    }
                    return new PageSetImpl<>(page, nextMarker);
                });

        doAnswer(invocation -> probe.call("delete", () -> {
            String path = invocation.getArgument(0);
            if (path.equals(probe.failingDelete)) {
                throw new IllegalStateException("delete failed: " + path);
            }
            blobs.remove(path);
            return null;
        })).when(storage).delete(anyString());

        when(storage.exists(anyString())).thenAnswer(invocation -> blobs.containsKey((String) invocation.getArgument(0)));

        when(storage.load(anyString())).thenAnswer(invocation -> {
            StoredBlob stored = blobs.get((String) invocation.getArgument(0));
            return stored == null ? null : stored.toBlob((String) invocation.getArgument(0));
        });

        when(storage.copy(anyString(), anyString(), isNull())).thenAnswer(invocation -> probe.call("copy", () -> {
            String from = invocation.getArgument(0);
            String to = invocation.getArgument(1);
            StoredBlob source = blobs.get(from);
            if (source == null) {
                return false;
            }
            blobs.put(to, source.copy());
            return true;
        }));

        doAnswer(invocation -> probe.call("upload", () -> {
            String path = invocation.getArgument(0);
            BlobAttributes attributes = invocation.getArgument(1);
            InputStream data = invocation.getArgument(2);
            byte[] bytes = data.readAllBytes();
            blobs.put(path, StoredBlob.of(attributes, bytes));
            return null;
        })).when(storage).upload(anyString(), any(BlobAttributes.class), any());

        doAnswer(invocation -> {
            String path = invocation.getArgument(0);
            String contentType = invocation.getArgument(1);
            String contentEncoding = invocation.getArgument(2);
            Map<String, String> userMetadata = invocation.getArgument(3);
            byte[] data = invocation.getArgument(4);
            blobs.put(path, StoredBlob.of(new BlobAttributes(contentType, contentEncoding, null, null, null, userMetadata), data));
            return null;
        }).when(storage).store(anyString(), org.mockito.ArgumentMatchers.nullable(String.class),
                org.mockito.ArgumentMatchers.nullable(String.class), any(), any());

        return storage;
    }

    private static StorageMetadata storageMetadata(String name, StorageType type) {
        MutableStorageMetadataImpl metadata = new MutableStorageMetadataImpl();
        metadata.setType(type);
        metadata.setName(name);
        return metadata;
    }

    /**
     * Records the fake's delete, copy and upload calls: how many run at once, the most that ever did, and the order
     * they start and end in. {@code delayMs} makes each call last long enough to overlap with the next.
     */
    private static final class CallProbe {
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger maxInFlight = new AtomicInteger();
        private final List<String> events = Collections.synchronizedList(new ArrayList<>());
        private volatile long delayMs;
        private volatile String failingDelete;

        <T> T call(String name, Callable<T> body) throws Exception {
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            events.add(name + ":start");
            try {
                if (delayMs > 0) {
                    Thread.sleep(delayMs);
                }
                return body.call();
            } finally {
                events.add(name + ":end");
                inFlight.decrementAndGet();
            }
        }
    }

    private record StoredBlob(byte[] data, BlobAttributes attributes) {
        static StoredBlob of(BlobAttributes attributes, byte[] data) {
            return new StoredBlob(data, attributes);
        }

        StoredBlob copy() {
            return new StoredBlob(data, attributes);
        }

        Blob toBlob(String path) {
            var builder = new BlobBuilderImpl().name(path).payload(data);
            if (attributes.contentType() != null) {
                builder.contentType(attributes.contentType());
            }
            if (attributes.contentEncoding() != null) {
                builder.contentEncoding(attributes.contentEncoding());
            }
            if (attributes.contentDisposition() != null) {
                builder.contentDisposition(attributes.contentDisposition());
            }
            if (attributes.contentLanguage() != null) {
                builder.contentLanguage(attributes.contentLanguage());
            }
            if (attributes.cacheControl() != null) {
                builder.cacheControl(attributes.cacheControl());
            }
            if (attributes.userMetadata() != null) {
                builder.userMetadata(attributes.userMetadata());
            }
            return builder.build();
        }
    }

    // ---- settings ----

    @Test
    void testDefaults() {
        AdminSystemImportService.Settings settings = new AdminSystemImportService.Settings();

        assertEquals(60_000, settings.getStallTimeout());
        assertEquals(15_000, settings.getProgressInterval());
        assertEquals(4, settings.getParallelism());
        assertDoesNotThrow(settings::validate);
    }

    @Test
    void testNonPositiveSettingsFailStartup() {
        AdminSystemImportService.Settings stallTimeout = new AdminSystemImportService.Settings();
        for (long invalid : new long[] {0, -1}) {
            stallTimeout.setStallTimeout(invalid);
            assertThrows(IllegalArgumentException.class, stallTimeout::validate, "stallTimeout=" + invalid);
        }

        AdminSystemImportService.Settings progressInterval = new AdminSystemImportService.Settings();
        for (long invalid : new long[] {0, -1}) {
            progressInterval.setProgressInterval(invalid);
            assertThrows(IllegalArgumentException.class, progressInterval::validate, "progressInterval=" + invalid);
        }

        AdminSystemImportService.Settings parallelism = new AdminSystemImportService.Settings();
        for (int invalid : new int[] {0, -1}) {
            parallelism.setParallelism(invalid);
            assertThrows(IllegalArgumentException.class, parallelism::validate, "parallelism=" + invalid);
        }
    }

    // ---- stage phase: manifest and version ----

    @Test
    void testSlowButAliveUploadIsAccepted() {
        byte[] archive = archiveOf(CORE_VERSION, entry("public/models/m1", "{}"));
        InputStream trickle = new InputStream() {
            private int position;

            @Override
            public int read() {
                if (position >= archive.length) {
                    return -1;
                }
                try {
                    Thread.sleep(5);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return archive[position++] & 0xFF;
            }
        };

        AdminSystemImportService.StagedImport staged = service.stage(trickle, ImportMode.REPLACE);

        assertEquals(1, staged.blobCount());
    }

    @Test
    void testArchiveFromNewerReleaseIsRejected() {
        byte[] archive = archiveOf("0.51.0", entry("public/models/m1", "{}"));
        assertRejected(archive);
        assertNoBlobs();
    }

    @Test
    void testArchiveFromSameOrOlderReleaseIsAccepted() {
        for (String version : List.of(CORE_VERSION, "0.49.2")) {
            byte[] archive = archiveOf(version, entry("public/models/m1", "{}"));
            AdminSystemImportService.StagedImport staged = service.stage(stream(archive), ImportMode.REPLACE);
            assertEquals(1, staged.blobCount());
            service.deleteStaging(staged);
        }
    }

    // ---- stage phase: sidecar pairing and duplicates ----

    @Test
    void testBlobWithoutMetadataIsRejected() {
        byte[] archive = zip(manifest(CORE_VERSION), blob("platform/models/m1", "{}"));
        assertRejected(archive);
    }

    @Test
    void testMetadataWithoutBlobIsRejected() {
        byte[] archive = zip(manifest(CORE_VERSION), metadata("public/files/x", "text/plain"));
        assertRejected(archive);
    }

    @Test
    void testDuplicateEntryIsRejected() {
        // java.util.zip.ZipOutputStream refuses to write two entries with the same name, so the duplicate is
        // produced by splicing: the service reads entries sequentially off ZipInputStream (it never consults the
        // central directory), so appending a second copy of one entry's local record is enough to trigger it,
        // and the service throws before ever needing a (valid) central directory or a clean end of stream.
        byte[] mainPart = localRecordsOnly(zip(manifest(CORE_VERSION),
                metadata("platform/roles/r1", "application/json"), blob("platform/roles/r1", "{}")));
        byte[] duplicateMetadata = localRecordsOnly(zip(metadata("platform/roles/r1", "application/json")));
        assertRejected(concat(mainPart, duplicateMetadata));
    }

    @Test
    void testEntryWithWrongDeclaredSizeIsRejected() {
        // ZipOutputStream itself refuses to write an entry whose declared size does not match, so this corrupts a
        // byte of the blob's compressed data instead: ZipInputStream detects the same class of error (the entry's
        // content no longer matches its header) and throws, which the service turns into 400 the same way.
        String path = "public/files/corrupt-target";
        byte[] archive = archiveOf(CORE_VERSION, entry(path, "x".repeat(500)));
        int nameIndex = indexOf(archive, path.getBytes(StandardCharsets.UTF_8));
        assertTrue(nameIndex >= 0);
        int dataIndex = nameIndex + path.length() + 8;
        archive[dataIndex] ^= 0xFF;

        assertRejected(archive);
    }

    private static byte[] localRecordsOnly(byte[] zipBytes) {
        int centralDirStart = indexOf(zipBytes, new byte[] {0x50, 0x4B, 0x01, 0x02});
        assertTrue(centralDirStart >= 0, "central directory signature not found");
        byte[] result = new byte[centralDirStart];
        System.arraycopy(zipBytes, 0, result, 0, centralDirStart);
        return result;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = new byte[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        outer:
        for (int i = 0; i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    @Test
    void testNotZipIsRejected() {
        assertRejected("hello, this is not a zip file at all".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testOnlyManifestIsRejected() {
        byte[] archive = zip(manifest(CORE_VERSION));
        assertRejected(archive);
        assertNoBlobs();
    }

    @Test
    void testTruncatedArchiveIsRejected() {
        byte[] whole = archiveOf(CORE_VERSION, entry("public/models/m1", "{\"a\":1}"));
        byte[] truncated = new byte[whole.length / 2];
        System.arraycopy(whole, 0, truncated, 0, truncated.length);
        assertRejected(truncated);
        assertNoBlobs();
    }

    @Test
    void testRejectedArchiveLeavesNoTraceInTheBuckets() {
        byte[] archive = zip(manifest(CORE_VERSION),
                metadata("public/models/good", "application/json"), blob("public/models/good", "{}"),
                blob("public/models/bad", "{}")); // no metadata before it
        assertRejected(archive);
        assertNoBlobs();
    }

    @Test
    void testStagedEntriesAreOutsideTheBuckets() {
        byte[] archive = archiveOf(CORE_VERSION, entry("public/models/m1", "{}"));
        AdminSystemImportService.StagedImport staged = service.stage(stream(archive), ImportMode.REPLACE);
        assertFalse(storage.exists("public/models/m1"));
        assertTrue(staged.stagingPrefix().startsWith(AdminSystemImportService.STAGING_FOLDER + "/"));
    }

    // ---- stage phase: stall ----

    @Test
    void testStalledUploadTimesOut() {
        // the real stall comes from InputStreamAdapter's own read timeout (InputStreamAdapterTest covers that);
        // here we only check that the service maps that specific exception to 408 and cleans up the staging.
        InputStream stalling = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new com.epam.aidial.core.server.vertx.stream.InputStreamAdapter.StallTimeoutException("no data for 100 ms");
            }
        };
        AdminSystemImportRejectedException e = assertThrows(AdminSystemImportRejectedException.class, () -> service.stage(stalling, ImportMode.REPLACE));
        assertEquals(HttpStatus.REQUEST_TIMEOUT, e.getStatus());
        assertNoBlobs();
    }

    // ---- replace phase ----

    @Test
    void testBlobAbsentFromArchiveIsRemoved() {
        storage.store("platform/models/old", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        runImport(archiveOf(CORE_VERSION, entry("platform/models/new", "{}")));

        assertFalse(storage.exists("platform/models/old"));
        assertTrue(storage.exists("platform/models/new"));
    }

    @Test
    void testBlobsEqualTheArchive() throws IOException {
        byte[] gz = gzip("{\"endpoint\":\"http://localhost\"}");
        byte[] archive = zip(manifest(CORE_VERSION),
                new RawEntry(".dial-system-export/metadata/platform/models/m1",
                        "{\"contentType\":\"application/json\",\"contentEncoding\":\"gzip\",\"userMetadata\":{\"etag\":\"abc\"}}"
                                .getBytes(StandardCharsets.UTF_8)),
                new RawEntry("platform/models/m1", gz));
        AdminSystemImportService.ImportResult result = runImport(archive);

        assertEquals(1, result.importedBlobs());
        var blob = storage.load("platform/models/m1");
        assertEquals("gzip", blob.getMetadata().getContentMetadata().getContentEncoding());
        assertEquals("abc", blob.getMetadata().getUserMetadata().get("etag"));
    }

    @Test
    void testCredentialsAreWiped() {
        storage.store("public/credentials/t1", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        storage.store("platform/credentials/t2", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}"), entry("platform/models/m1", "{}")));

        assertFalse(storage.exists("public/credentials/t1"));
        assertFalse(storage.exists("platform/credentials/t2"));
    }

    @Test
    void testEncryptionKeysSurvive() {
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), "key-bytes".getBytes(StandardCharsets.UTF_8));
        storage.store("public/encryption_keys/cek", "application/octet-stream", null, Map.of(), "key-bytes-2".getBytes(StandardCharsets.UTF_8));
        runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}"), entry("platform/models/m1", "{}")));

        assertTrue(storage.exists("platform/encryption_keys/cek"));
        assertTrue(storage.exists("public/encryption_keys/cek"));
    }

    @Test
    void testBucketWithoutEntriesIsLeftUntouched() {
        storeOld("public/models/old");
        storeOld("platform/models/old");

        AdminSystemImportService.ImportResult result = runImport(archiveOf(CORE_VERSION, entry("platform/models/new", "{}")));

        assertTrue(storage.exists("public/models/old"));
        assertFalse(storage.exists("platform/models/old"));
        assertTrue(storage.exists("platform/models/new"));
        assertEquals(1, result.deletedBlobs());
        Mockito.verify(contentEncryptionKeyService).invalidateKey(new BucketInfo("platform", "platform/"));
        Mockito.verify(contentEncryptionKeyService, Mockito.never()).invalidateKey(new BucketInfo("public", "public/"));
    }

    @Test
    void testEncryptionKeyFromTheArchiveReplacesTheTargetKey() {
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), "old-key".getBytes(StandardCharsets.UTF_8));
        storage.store("public/encryption_keys/cek", "application/octet-stream", null, Map.of(), "public-key".getBytes(StandardCharsets.UTF_8));
        AdminSystemImportService kmsService = newService(new AdminSystemImportService.Settings(), true);
        byte[] archive = archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"), entry("platform/encryption_keys/cek", "wrapped-key"));

        kmsService.replace(kmsService.stage(stream(archive), ImportMode.REPLACE), new AdminSystemImportService.Progress(ImportMode.REPLACE));

        assertArrayEquals("wrapped-key".getBytes(StandardCharsets.UTF_8), readBytes("platform/encryption_keys/cek"));
        assertArrayEquals("public-key".getBytes(StandardCharsets.UTF_8), readBytes("public/encryption_keys/cek"));
        Mockito.verify(keyManagementService).decrypt("wrapped-key".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void testEncryptionKeyThatCannotBeUnwrappedIsRejected() {
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), "old-key".getBytes(StandardCharsets.UTF_8));
        when(keyManagementService.decrypt(any())).thenThrow(new CekEncryptionException("Decryption error", new RuntimeException()));
        AdminSystemImportService kmsService = newService(new AdminSystemImportService.Settings(), true);
        byte[] archive = archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"), entry("platform/encryption_keys/cek", "other-env-key"));

        assertThrows(AdminSystemImportRejectedException.class, () -> kmsService.stage(stream(archive), ImportMode.REPLACE));

        assertArrayEquals("old-key".getBytes(StandardCharsets.UTF_8), readBytes("platform/encryption_keys/cek"));
        assertFalse(storage.exists("platform/models/m1"));
    }

    @Test
    void testFolderMarkersAreRestored() {
        byte[] archive = zip(manifest(CORE_VERSION),
                metadata("public/skills/folder/.dial-folder", "application/json"), blob("public/skills/folder/.dial-folder", "{}"),
                metadata("public/skills/folder/skill/.dial-resource", "application/json"),
                blob("public/skills/folder/skill/.dial-resource", "{}"));
        runImport(archive);

        assertTrue(storage.exists("public/skills/folder/.dial-folder"));
        assertTrue(storage.exists("public/skills/folder/skill/.dial-resource"));
    }

    @Test
    void testFolderListingEntriesAreSkippedDuringWipeAndPromote() {
        storage.store("public/models/old", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        folderEntries.add("public/files/user1/dir");

        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/new", "{}"))), ImportMode.REPLACE);
        folderEntries.add(staged.stagingPrefix() + "public/files/user1/dir");

        service.replace(staged, new AdminSystemImportService.Progress(ImportMode.REPLACE));

        Mockito.verify(storage, Mockito.never()).delete("public/files/user1/dir");
        Mockito.verify(storage, Mockito.never()).copy(eq(staged.stagingPrefix() + "public/files/user1/dir"), any(), isNull());
        assertFalse(storage.exists("public/files/user1/dir"));
        assertTrue(storage.exists("public/models/new"));
    }

    @Test
    void testOtherBucketsAreUntouched() {
        storage.store("Users/alice/files/a.txt", "text/plain", null, Map.of(), "a".getBytes(StandardCharsets.UTF_8));
        storage.store("Keys/k1", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}")));

        assertTrue(storage.exists("Users/alice/files/a.txt"));
        assertTrue(storage.exists("Keys/k1"));
    }

    @Test
    void testReplaceOrderIsWipePromoteInvalidateRebuild() {
        storage.store("public/models/old", "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/new", "{}"))), ImportMode.REPLACE);
        AdminSystemImportService.Progress progress = new AdminSystemImportService.Progress(ImportMode.REPLACE);

        AdminSystemImportService.ImportResult result = service.replace(staged, progress);

        // the final phase observed after replace() returns is REBUILD, the last of the four in order
        assertEquals(AdminSystemImportService.Progress.Phase.REBUILD, progress.getPhase());
        assertEquals(1, result.deletedBlobs());
        assertEquals(1, result.importedBlobs());
    }

    @Test
    void testProgressReportsPhasesAndCounters() {
        for (int i = 0; i < 3; i++) {
            storage.store("public/models/old" + i, "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
        }
        byte[] archive = archiveOf(CORE_VERSION,
                entry("public/models/n1", "{}"), entry("public/models/n2", "{}"), entry("public/models/n3", "{}"));
        AdminSystemImportService.StagedImport staged = service.stage(stream(archive), ImportMode.REPLACE);
        assertEquals(3, staged.blobCount());

        AdminSystemImportService.Progress progress = new AdminSystemImportService.Progress(ImportMode.REPLACE);
        AdminSystemImportService.ImportResult result = service.replace(staged, progress);

        assertEquals(3, result.importedBlobs());
        assertEquals(3, result.deletedBlobs());
        assertEquals(3, progress.getTotal());
        assertEquals(3, progress.getCopied());
        assertEquals(3, progress.getDeleted());
        assertEquals(AdminSystemImportService.Progress.Phase.REBUILD, progress.getPhase());
    }

    @Test
    void testRebuildFailureIsReportedAsWarning() {
        when(mergedConfigStore.rebuildNowUnderBucketLocks()).thenThrow(new RuntimeException("rebuild boom"));
        AdminSystemImportService.ImportResult result = runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}")));

        assertEquals(1, result.warnings().size());
        assertTrue(storage.exists("public/models/m1"), "the blobs stay replaced even if the rebuild fails");
    }

    @Test
    void testOtherPodsAreToldToRebuild() {
        runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}")));

        Mockito.verify(mergedConfigStore).requestRebuildOnAllPods();
    }

    @Test
    void testFailureToTellOtherPodsIsReportedAsWarning() {
        Mockito.doThrow(new RuntimeException("redis down")).when(mergedConfigStore).requestRebuildOnAllPods();

        AdminSystemImportService.ImportResult result = runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}")));

        assertEquals(1, result.warnings().size());
        assertTrue(storage.exists("public/models/m1"), "the blobs stay replaced");
    }

    @Test
    void testSlowReplaceIsNotAborted() {
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/m1", "{}"))), ImportMode.REPLACE);
        when(mergedConfigStore.rebuildNowUnderBucketLocks()).thenAnswer(invocation -> {
            clock.addAndGet(LockService.LOCK_PERIOD.plusSeconds(10).toMillis());
            return null;
        });

        AdminSystemImportService.ImportResult result = service.replace(staged, new AdminSystemImportService.Progress(ImportMode.REPLACE));

        assertEquals(1, result.importedBlobs());
        assertTrue(storage.exists("public/models/m1"));
    }

    @Test
    void testImportTwiceGivesTheSameState() {
        byte[] archive = archiveOf(CORE_VERSION, entry("public/models/m1", "{\"a\":1}"));
        runImport(archive);
        var firstBlob = readBytes("public/models/m1");

        runImport(archive);
        var secondBlob = readBytes("public/models/m1");

        assertArrayEquals(firstBlob, secondBlob);
        assertTrue(storage.exists("public/models/m1"));
    }

    // ---- bounded parallel storage calls ----

    @Test
    void testStorageCallsRunInParallelUpToTheLimit() {
        Entry[] entries = new Entry[7];
        for (int i = 0; i < 6; i++) {
            storeOld("public/models/old" + i);
            entries[i] = entry("public/models/n" + i, "{}");
        }
        entries[6] = entry("public/files/large.bin", "x".repeat(5 * 1024 * 1024 + 1));
        AdminSystemImportService.Settings settings = new AdminSystemImportService.Settings();
        settings.setParallelism(2);
        AdminSystemImportService parallelService = newService(settings);
        probe.delayMs = 20;

        AdminSystemImportService.StagedImport staged = parallelService.stage(stream(archiveOf(CORE_VERSION, entries)), ImportMode.REPLACE);
        parallelService.replace(staged, new AdminSystemImportService.Progress(ImportMode.REPLACE));

        assertEquals(2, probe.maxInFlight.get());
    }

    @Test
    void testCopyStartsAfterTheLastDelete() {
        for (int i = 0; i < 4; i++) {
            storeOld("public/models/old" + i);
        }
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION,
                entry("public/models/n1", "{}"), entry("public/models/n2", "{}"))), ImportMode.REPLACE);
        probe.events.clear();
        probe.delayMs = 20;

        service.replace(staged, new AdminSystemImportService.Progress(ImportMode.REPLACE));

        List<String> events = List.copyOf(probe.events);
        assertTrue(events.lastIndexOf("delete:end") < events.indexOf("copy:start"), events::toString);
    }

    @Test
    void testFailedDeleteFailsTheReplaceAfterCallsInFlight() {
        for (int i = 0; i < 4; i++) {
            storeOld("public/models/old" + i);
        }
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/n1", "{}"))), ImportMode.REPLACE);
        probe.events.clear();
        probe.delayMs = 20;
        probe.failingDelete = "public/models/old0";

        assertThrows(RuntimeException.class, () -> service.replace(staged, new AdminSystemImportService.Progress(ImportMode.REPLACE)));

        assertEquals(0, probe.inFlight.get(), "every call in flight ends before the replace fails");
        assertFalse(probe.events.contains("copy:start"));
    }

    private void storeOld(String path) {
        storage.store(path, "application/json", null, Map.of(), "{}".getBytes(StandardCharsets.UTF_8));
    }

    // ---- mode ----

    @Test
    void testModeDefaultsToMergeAndIsCaseInsensitive() {
        assertEquals(ImportMode.MERGE, ImportMode.parse(null));
        assertEquals(ImportMode.MERGE, ImportMode.parse("merge"));
        assertEquals(ImportMode.REPLACE, ImportMode.parse("replace"));
        assertEquals(ImportMode.REPLACE, ImportMode.parse("REPLACE"));
        assertEquals(ImportMode.MERGE, ImportMode.parse("Merge"));
    }

    @Test
    void testUnknownOrEmptyModeIsRejected() {
        assertEquals(HttpStatus.BAD_REQUEST, assertThrows(AdminSystemImportRejectedException.class, () -> ImportMode.parse("wipe")).getStatus());
        assertThrows(AdminSystemImportRejectedException.class, () -> ImportMode.parse(""));
    }

    @Test
    void testMergeKeepsBlobAbsentFromArchive() {
        storeOld("platform/models/old");

        AdminSystemImportService.ImportResult result = runImport(archiveOf(CORE_VERSION, entry("platform/models/m1", "{}")), ImportMode.MERGE);

        assertTrue(storage.exists("platform/models/old"));
        assertTrue(storage.exists("platform/models/m1"));
        assertEquals(0, result.deletedBlobs());
        assertEquals(1, result.importedBlobs());
    }

    @Test
    void testMergeKeepsCredentials() {
        storeOld("platform/credentials/t2");

        runImport(archiveOf(CORE_VERSION, entry("platform/models/m1", "{}")), ImportMode.MERGE);

        assertTrue(storage.exists("platform/credentials/t2"));
    }

    @Test
    void testMergeOverwritesExistingBlob() {
        storage.store("platform/models/m1", "text/plain", null, Map.of("k", "old"), "old".getBytes(StandardCharsets.UTF_8));

        AdminSystemImportService.ImportResult result = runImport(archiveOf(CORE_VERSION, entry("platform/models/m1", "{\"v\":1}")), ImportMode.MERGE);

        assertArrayEquals("{\"v\":1}".getBytes(StandardCharsets.UTF_8), readBytes("platform/models/m1"));
        assertEquals(0, result.deletedBlobs());
    }

    @Test
    void testMergeWritesNoWipeProgress() {
        storeOld("platform/models/old");
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"))), ImportMode.MERGE);
        AdminSystemImportService.Progress progress = new AdminSystemImportService.Progress(ImportMode.MERGE);

        assertEquals(AdminSystemImportService.Progress.Phase.COPY, progress.getPhase());
        service.replace(staged, progress);

        assertEquals(0, progress.getDeleted());
        Mockito.verify(storage, Mockito.never()).delete("platform/models/old");
    }

    @Test
    void testMergeInvalidatesOnlyImportedBlobs() {
        ResourceDescriptor imported = role("r1");
        ResourceDescriptor other = role("r2");
        resourceService.putResource(imported, "{}", EtagHeader.NEW_ONLY);
        resourceService.putResource(other, "{}", EtagHeader.NEW_ONLY);
        assertTrue(isQueued(imported) && isQueued(other));

        runImport(archiveOf(CORE_VERSION, entry("platform/roles/r1", "{\"v\":\"imported\"}")), ImportMode.MERGE);

        assertFalse(isCached(imported));
        assertFalse(isQueued(imported));
        assertTrue(isCached(other), "a resource that the archive does not hold keeps its cached entry");
        assertTrue(isQueued(other), "a resource that the archive does not hold keeps its queued write");
    }

    @Test
    void testReplaceStillInvalidatesTheBuckets() {
        ResourceDescriptor imported = role("r1");
        ResourceDescriptor other = role("r2");
        resourceService.putResource(imported, "{}", EtagHeader.NEW_ONLY);
        resourceService.putResource(other, "{}", EtagHeader.NEW_ONLY);

        runImport(archiveOf(CORE_VERSION, entry("platform/roles/r1", "{}")), ImportMode.REPLACE);

        assertFalse(isCached(other));
        assertFalse(isQueued(other));
    }

    @Test
    void testMergeKeepsTheKeyOfTheBucketThatHasOne() {
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), "old-key".getBytes(StandardCharsets.UTF_8));
        when(keyManagementService.decrypt(any())).thenThrow(new CekEncryptionException("Decryption error", new RuntimeException()));
        byte[] archive = archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"), entry("platform/encryption_keys/cek", "other-env-key"));

        AdminSystemImportService.ImportResult result = runKmsImport(archive, ImportMode.MERGE);

        assertArrayEquals("old-key".getBytes(StandardCharsets.UTF_8), readBytes("platform/encryption_keys/cek"));
        assertTrue(storage.exists("platform/models/m1"));
        Mockito.verify(keyManagementService, Mockito.never()).decrypt(any());
        Mockito.verify(contentEncryptionKeyService, Mockito.never()).invalidateKey(any());
        assertEquals(1, result.importedBlobs());
        assertEquals(1, result.warnings().size());
        assertTrue(result.warnings().get(0).contains("platform/encryption_keys/cek"));
    }

    @Test
    void testMergeTakesTheKeyOfTheBucketWithoutOne() {
        byte[] archive = archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"), entry("platform/encryption_keys/cek", "wrapped-key"));

        AdminSystemImportService.ImportResult result = runKmsImport(archive, ImportMode.MERGE);

        assertArrayEquals("wrapped-key".getBytes(StandardCharsets.UTF_8), readBytes("platform/encryption_keys/cek"));
        Mockito.verify(keyManagementService).decrypt("wrapped-key".getBytes(StandardCharsets.UTF_8));
        Mockito.verify(contentEncryptionKeyService).invalidateKey(new BucketInfo("platform", "platform/"));
        Mockito.verify(contentEncryptionKeyService, Mockito.never()).invalidateKey(new BucketInfo("public", "public/"));
        assertTrue(result.warnings().isEmpty());
    }

    @Test
    void testMergeRejectsAnUnwrappableKeyOfTheBucketWithoutOne() {
        when(keyManagementService.decrypt(any())).thenThrow(new CekEncryptionException("Decryption error", new RuntimeException()));
        byte[] archive = archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"), entry("platform/encryption_keys/cek", "other-env-key"));

        assertThrows(AdminSystemImportRejectedException.class, () -> runKmsImport(archive, ImportMode.MERGE));

        assertFalse(storage.exists("platform/encryption_keys/cek"));
        assertFalse(storage.exists("platform/models/m1"));
    }

    @Test
    void testMergeWithoutKeyAsksNoKms() {
        runKmsImport(archiveOf(CORE_VERSION, entry("platform/models/m1", "{}")), ImportMode.MERGE);

        Mockito.verify(keyManagementService, Mockito.never()).decrypt(any());
        Mockito.verify(contentEncryptionKeyService, Mockito.never()).invalidateKey(any());
    }

    @Test
    void testMergeSkipsKeyThatAppearedBeforeTheReplace() {
        AdminSystemImportService kmsService = newService(new AdminSystemImportService.Settings(), true);
        byte[] archive = archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"), entry("platform/encryption_keys/cek", "wrapped-key"));
        AdminSystemImportService.StagedImport staged = kmsService.stage(stream(archive), ImportMode.MERGE);
        assertEquals(2, staged.blobCount());
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), "new-key".getBytes(StandardCharsets.UTF_8));

        AdminSystemImportService.ImportResult result = kmsService.replace(staged, new AdminSystemImportService.Progress(ImportMode.MERGE));

        assertArrayEquals("new-key".getBytes(StandardCharsets.UTF_8), readBytes("platform/encryption_keys/cek"));
        assertEquals(1, result.importedBlobs());
        assertEquals(1, result.warnings().size());
        Mockito.verify(contentEncryptionKeyService, Mockito.never()).invalidateKey(any());
    }

    @Test
    void testMergeInvalidatesImportedBlobsOfEveryListingPage() {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 1200; i++) {
            entries.add(entry("platform/roles/r" + i, "{}"));
        }
        resourceService.putResource(role("r1100"), "{}", EtagHeader.NEW_ONLY);
        resourceService.putResource(role("kept"), "{}", EtagHeader.NEW_ONLY);

        runImport(archiveOf(CORE_VERSION, entries.toArray(new Entry[0])), ImportMode.MERGE);

        assertFalse(isQueued(role("r1100")), "a blob on the second listing page is invalidated too");
        assertTrue(isQueued(role("kept")));
    }

    @Test
    void testStageReadsTheWholeBody() {
        byte[] archive = archiveOf(CORE_VERSION, entry("platform/models/m1", "{}"));
        byte[] withTail = Arrays.copyOf(archive, archive.length + 10_000);
        ByteArrayInputStream body = new ByteArrayInputStream(withTail);

        service.stage(body, ImportMode.MERGE);

        assertEquals(0, body.available());
    }

    @Test
    void testWipeKeepsEncryptionKeysWhateverTheCase() {
        storage.store("platform/Encryption_Keys/cek", "application/octet-stream", null, Map.of(), "key".getBytes(StandardCharsets.UTF_8));
        storeOld("platform/models/old");

        runImport(archiveOf(CORE_VERSION, entry("platform/models/m1", "{}")), ImportMode.REPLACE);

        assertTrue(storage.exists("platform/Encryption_Keys/cek"));
        assertFalse(storage.exists("platform/models/old"));
    }

    @Test
    void testWipeDeletesAnEntryThatOnlyStartsLikeTheKeysGroup() {
        storeOld("platform/encryption_keysX/y");

        runImport(archiveOf(CORE_VERSION, entry("platform/models/m1", "{}")), ImportMode.REPLACE);

        assertFalse(storage.exists("platform/encryption_keysX/y"));
    }

    // ---- guard ----

    @Test
    void testSecondImportIsRejectedWhileOneHoldsTheGuard() {
        LockService.Lock guard = service.tryLockGuard();
        assertNotNull(guard);
        try {
            assertNull(service.tryLockGuard());
        } finally {
            guard.close();
        }
    }

    @Test
    void testGuardIsReleasedAfterRejection() {
        LockService.Lock guard = service.tryLockGuard();
        assertNotNull(guard);
        guard.close();

        LockService.Lock secondGuard = service.tryLockGuard();
        assertNotNull(secondGuard, "the guard must be free for the next system import");
        secondGuard.close();
    }

    @Test
    void testImportIntoAnEnvironmentWithoutKeysIsAccepted() {
        // no encryption_keys/cek blob exists anywhere in the target
        AdminSystemImportService.ImportResult result = runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}")));
        assertEquals(1, result.importedBlobs());
    }

    // ---- staging cleanup ----

    @Test
    void testStagingIsRemovedAfterSuccess() {
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/m1", "{}"))), ImportMode.REPLACE);
        service.replace(staged, new AdminSystemImportService.Progress(ImportMode.REPLACE));
        service.deleteStaging(staged);

        assertFalse(storage.exists(staged.stagingPrefix() + "public/models/m1"));
    }

    @Test
    void testStagingIsRemovedAfterRejection() {
        byte[] badArchive = zip(manifest(CORE_VERSION), metadata("public/models/m1", "application/json"));
        // stage() already deletes its own staging on rejection; verify nothing is left for ANY prefix
        assertThrows(AdminSystemImportRejectedException.class, () -> service.stage(stream(badArchive), ImportMode.REPLACE));
        assertNoBlobs();
    }

    // ---- helpers ----

    private AdminSystemImportService.ImportResult runImport(byte[] archive) {
        return runImport(archive, ImportMode.REPLACE);
    }

    private AdminSystemImportService.ImportResult runImport(byte[] archive, ImportMode mode) {
        AdminSystemImportService.StagedImport staged = service.stage(stream(archive), mode);
        return service.replace(staged, new AdminSystemImportService.Progress(mode));
    }

    private AdminSystemImportService.ImportResult runKmsImport(byte[] archive, ImportMode mode) {
        AdminSystemImportService kmsService = newService(new AdminSystemImportService.Settings(), true);
        return kmsService.replace(kmsService.stage(stream(archive), mode), new AdminSystemImportService.Progress(mode));
    }

    private static ResourceDescriptor role(String name) {
        return new ResourceDescriptor(ResourceTypes.ROLE, name, List.of(), "platform", "platform/", false);
    }

    private boolean isCached(ResourceDescriptor descriptor) {
        return redis.getMap(RedisUtil.redisKey(descriptor, null)).isExists();
    }

    private boolean isQueued(ResourceDescriptor descriptor) {
        return redis.getScoredSortedSet("resource:queue", StringCodec.INSTANCE).contains(RedisUtil.redisKey(descriptor, null));
    }

    private byte[] readBytes(String path) {
        try (InputStream in = storage.load(path).getPayload().openStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void assertNoBlobs() {
        for (String location : List.of("public/", "platform/")) {
            var page = storage.list(location, null, 1000, true);
            for (StorageMetadata meta : page) {
                if (meta.getType() == StorageType.BLOB) {
                    throw new AssertionError("Unexpected blob in " + location + ": " + meta.getName());
                }
            }
        }
    }

    private AdminSystemImportRejectedException assertRejected(byte[] archive) {
        return assertThrows(AdminSystemImportRejectedException.class, () -> service.stage(stream(archive), ImportMode.REPLACE));
    }

    private static InputStream stream(byte[] archive) {
        return new ByteArrayInputStream(archive);
    }

    private byte[] archiveOf(String coreVersion, Entry... entries) {
        List<RawEntry> raw = new ArrayList<>();
        raw.add(manifest(coreVersion));
        for (Entry e : entries) {
            raw.add(metadata(e.path(), "application/json"));
            raw.add(blob(e.path(), e.content()));
        }
        return zip(raw.toArray(new RawEntry[0]));
    }

    private static Entry entry(String path, String content) {
        return new Entry(path, content);
    }

    private record Entry(String path, String content) {
    }

    private static RawEntry manifest(String coreVersion) {
        return new RawEntry(".dial-system-export/manifest.json",
                ("{\"formatVersion\":1,\"coreVersion\":\"" + coreVersion + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static RawEntry metadata(String path, String contentType) {
        return new RawEntry(".dial-system-export/metadata/" + path, ("{\"contentType\":\"" + contentType + "\"}").getBytes(StandardCharsets.UTF_8));
    }

    private static RawEntry blob(String path, String content) {
        return new RawEntry(path, content.getBytes(StandardCharsets.UTF_8));
    }

    private static void write(ZipOutputStream zip, RawEntry entry) throws IOException {
        zip.putNextEntry(new ZipEntry(entry.name()));
        zip.write(entry.bytes());
        zip.closeEntry();
    }

    private static byte[] zip(RawEntry... entries) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (RawEntry entry : entries) {
                write(zip, entry);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out.toByteArray();
    }

    private static byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
            gz.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private record RawEntry(String name, byte[] bytes) {
    }

    private static final class RedisServerHandle {
        private final RedisServer server;

        private RedisServerHandle(RedisServer server) {
            this.server = server;
        }

        static RedisServerHandle start(int port) throws IOException {
            RedisServer server = RedisServer.newRedisServer()
                    .port(port).bind("127.0.0.1")
                    .setting("maxmemory 8M").setting("maxmemory-policy volatile-lfu").build();
            server.start();
            return new RedisServerHandle(server);
        }

        void stop() throws IOException {
            server.stop();
        }
    }
}
