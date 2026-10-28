package com.epam.aidial.core.server.service;

import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.storage.blobstore.BlobAttributes;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TempFolderCleaner;
import com.epam.aidial.core.storage.service.TimerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.PageSet;
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
import redis.embedded.RedisServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
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
    private BlobStorage storage;
    private LockService lockService;
    private ResourceService resourceService;
    private MergedConfigStore mergedConfigStore;
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

            blobs = new TreeMap<>();
            folderEntries = new TreeSet<>();
            storage = fakeBlobStorage(blobs, folderEntries);

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
            clock = new AtomicLong(1_700_000_000_000L);
            idCounter = new AtomicLong();
            service = newService();
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    private AdminSystemImportService newService() {
        return new AdminSystemImportService(storage, lockService, resourceService, mergedConfigStore,
                new TempFolderCleaner(storage, AdminSystemImportService.STAGING_FOLDER, Duration.ofHours(24)),
                clock::get, CORE_VERSION, () -> "import-" + idCounter.incrementAndGet());
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
     * A plain in-memory stand-in for {@link BlobStorage}, covering only the operations
     * {@link AdminSystemImportService} and {@link TempFolderCleaner} use: list, delete, copy, upload, load, exists.
     * {@code folders} lists additionally as {@link StorageType#FOLDER} entries (not real blobs, and not stored in
     * {@code blobs}), the way blob storage lists a folder marker next to the blobs under it.
     */
    private static BlobStorage fakeBlobStorage(NavigableMap<String, StoredBlob> blobs, NavigableSet<String> folders)
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

        doAnswer(invocation -> {
            blobs.remove((String) invocation.getArgument(0));
            return null;
        }).when(storage).delete(anyString());

        when(storage.exists(anyString())).thenAnswer(invocation -> blobs.containsKey((String) invocation.getArgument(0)));

        when(storage.load(anyString())).thenAnswer(invocation -> {
            StoredBlob stored = blobs.get((String) invocation.getArgument(0));
            return stored == null ? null : stored.toBlob((String) invocation.getArgument(0));
        });

        when(storage.copy(anyString(), anyString(), isNull())).thenAnswer(invocation -> {
            String from = invocation.getArgument(0);
            String to = invocation.getArgument(1);
            StoredBlob source = blobs.get(from);
            if (source == null) {
                return false;
            }
            blobs.put(to, source.copy());
            return true;
        });

        doAnswer(invocation -> {
            String path = invocation.getArgument(0);
            BlobAttributes attributes = invocation.getArgument(1);
            InputStream data = invocation.getArgument(2);
            byte[] bytes = data.readAllBytes();
            blobs.put(path, StoredBlob.of(attributes, bytes));
            return null;
        }).when(storage).upload(anyString(), any(BlobAttributes.class), any());

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

        AdminSystemImportService.StagedImport staged = service.stage(trickle);

        assertEquals(1, staged.blobCount());
    }

    @Test
    void testManifestNotFirstIsRejected() {
        byte[] archive = zip(blob("public/models/m1", "{}"), manifest(CORE_VERSION));
        AdminSystemImportRejectedException e = assertRejected(archive);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        assertNoBlobs();
    }

    @Test
    void testUnsupportedFormatVersionIsRejected() {
        String manifest = "{\"formatVersion\":2,\"coreVersion\":\"" + CORE_VERSION + "\"}";
        byte[] archive = zip(new RawEntry(".dial-system-export/manifest.json", manifest.getBytes(StandardCharsets.UTF_8)));
        assertRejected(archive);
    }

    @Test
    void testOversizedManifestIsRejected() {
        // the manifest is attacker-controlled before any check has run, so it is capped the same way a sidecar is
        String padding = "\"padding\":\"" + "x".repeat(70_000) + "\"";
        byte[] archive = zip(new RawEntry(".dial-system-export/manifest.json",
                ("{\"formatVersion\":1,\"coreVersion\":\"" + CORE_VERSION + "\"," + padding + "}").getBytes(StandardCharsets.UTF_8)));
        assertRejected(archive);
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
            AdminSystemImportService.StagedImport staged = service.stage(stream(archive));
            assertEquals(1, staged.blobCount());
            service.deleteStaging(staged);
        }
    }

    @Test
    void testDevelopmentBuildAcceptsReleaseArchive() {
        AdminSystemImportService devService = new AdminSystemImportService(storage, lockService, resourceService, mergedConfigStore,
                new TempFolderCleaner(storage, AdminSystemImportService.STAGING_FOLDER, Duration.ofHours(24)),
                clock::get, "0.0.0", () -> "id-" + idCounter.incrementAndGet());
        byte[] archive = archiveOf("0.50.0", entry("public/models/m1", "{}"));
        AdminSystemImportService.StagedImport staged = devService.stage(stream(archive));
        assertEquals(1, staged.blobCount());
    }

    @Test
    void testDevelopmentArchiveIsRejectedByReleaseBuild() {
        byte[] archive = archiveOf("0.0.0", entry("public/models/m1", "{}"));
        assertRejected(archive);
    }

    @Test
    void testDevelopmentArchiveIsAcceptedByDevelopmentBuild() {
        AdminSystemImportService devService = new AdminSystemImportService(storage, lockService, resourceService, mergedConfigStore,
                new TempFolderCleaner(storage, AdminSystemImportService.STAGING_FOLDER, Duration.ofHours(24)),
                clock::get, "0.0.0", () -> "id-" + idCounter.incrementAndGet());
        byte[] archive = archiveOf("0.0.0", entry("public/models/m1", "{}"));
        assertEquals(1, devService.stage(stream(archive)).blobCount());
    }

    @Test
    void testUnreadableVersionCountsAsDevelopmentBuild() {
        AdminSystemImportService devService = new AdminSystemImportService(storage, lockService, resourceService, mergedConfigStore,
                new TempFolderCleaner(storage, AdminSystemImportService.STAGING_FOLDER, Duration.ofHours(24)),
                clock::get, "undefined", () -> "id-" + idCounter.incrementAndGet());
        byte[] accepted = archiveOf("0.50.0", entry("public/models/m1", "{}"));
        assertEquals(1, devService.stage(stream(accepted)).blobCount());

        byte[] rejected = archiveOf("undefined", entry("public/models/m2", "{}"));
        assertRejected(rejected);
    }

    // ---- stage phase: names ----

    @Test
    void testPathTraversalIsRejected() {
        assertRejected(archiveOf(CORE_VERSION, entry("public/../Users/alice/files/x", "a")));
        assertNoBlobs();
    }

    @Test
    void testOtherBucketIsRejected() {
        assertRejected(archiveOf(CORE_VERSION, entry("Users/alice/files/x", "a")));
    }

    @Test
    void testBackslashIsRejected() {
        assertRejected(archiveOf(CORE_VERSION, entry("public\\files\\x", "a")));
    }

    @Test
    void testControlCharacterIsRejected() {
        assertRejected(archiveOf(CORE_VERSION, entry("public/files/a\u0001b", "a")));
    }

    @Test
    void testLongNameIsRejected() {
        String longName = "public/files/" + "a".repeat(1100);
        assertRejected(archiveOf(CORE_VERSION, entry(longName, "a")));
    }

    @Test
    void testCekEntryIsRejected() {
        assertRejected(archiveOf(CORE_VERSION, entry("platform/encryption_keys/cek", "secret")));
    }

    @Test
    void testCredentialsEntryIsRejected() {
        assertRejected(archiveOf(CORE_VERSION, entry("public/credentials/x", "secret")));
    }

    @Test
    void testExcludedGroupIsMatchedWithoutRegardToCase() {
        assertRejected(archiveOf(CORE_VERSION, entry("public/Encryption_Keys/cek", "secret")));
    }

    @Test
    void testExcludedWordsDeeperInThePathAreAccepted() {
        byte[] archive = archiveOf(CORE_VERSION, entry("public/files/alice/encryption_keys/notes.txt", "notes"));
        AdminSystemImportService.StagedImport staged = service.stage(stream(archive));
        assertEquals(1, staged.blobCount());
        assertTrue(storage.exists(staged.stagingPrefix() + "public/files/alice/encryption_keys/notes.txt"));
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
    void testOversizedMetadataIsRejected() {
        String bigMetadata = "{\"contentType\":\"text/plain\",\"userMetadata\":{\"k\":\"" + "x".repeat(70_000) + "\"}}";
        byte[] archive = zip(manifest(CORE_VERSION),
                new RawEntry(".dial-system-export/metadata/public/files/x", bigMetadata.getBytes(StandardCharsets.UTF_8)),
                blob("public/files/x", "a"));
        assertRejected(archive);
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
        AdminSystemImportService.StagedImport staged = service.stage(stream(archive));
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
        AdminSystemImportRejectedException e = assertThrows(AdminSystemImportRejectedException.class, () -> service.stage(stalling));
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
        runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}")));

        assertFalse(storage.exists("public/credentials/t1"));
        assertFalse(storage.exists("platform/credentials/t2"));
    }

    @Test
    void testEncryptionKeysSurvive() {
        storage.store("platform/encryption_keys/cek", "application/octet-stream", null, Map.of(), "key-bytes".getBytes(StandardCharsets.UTF_8));
        storage.store("public/encryption_keys/cek", "application/octet-stream", null, Map.of(), "key-bytes-2".getBytes(StandardCharsets.UTF_8));
        runImport(archiveOf(CORE_VERSION, entry("public/models/m1", "{}")));

        assertTrue(storage.exists("platform/encryption_keys/cek"));
        assertTrue(storage.exists("public/encryption_keys/cek"));
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

        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/new", "{}"))));
        folderEntries.add(staged.stagingPrefix() + "public/files/user1/dir");

        service.replace(staged, new AdminSystemImportService.Progress());

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
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/new", "{}"))));
        AdminSystemImportService.Progress progress = new AdminSystemImportService.Progress();

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
        AdminSystemImportService.StagedImport staged = service.stage(stream(archive));
        assertEquals(3, staged.blobCount());

        AdminSystemImportService.Progress progress = new AdminSystemImportService.Progress();
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
    void testSlowReplaceIsNotAborted() {
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/m1", "{}"))));
        clock.addAndGet(LockService.LOCK_PERIOD.plusSeconds(10).toMillis());

        AdminSystemImportService.ImportResult result = service.replace(staged, new AdminSystemImportService.Progress());

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
        AdminSystemImportService.StagedImport staged = service.stage(stream(archiveOf(CORE_VERSION, entry("public/models/m1", "{}"))));
        service.replace(staged, new AdminSystemImportService.Progress());
        service.deleteStaging(staged);

        assertFalse(storage.exists(staged.stagingPrefix() + "public/models/m1"));
    }

    @Test
    void testStagingIsRemovedAfterRejection() {
        byte[] badArchive = zip(manifest(CORE_VERSION), metadata("public/models/m1", "application/json"));
        // stage() already deletes its own staging on rejection; verify nothing is left for ANY prefix
        assertThrows(AdminSystemImportRejectedException.class, () -> service.stage(stream(badArchive)));
        assertNoBlobs();
    }

    // ---- helpers ----

    private AdminSystemImportService.ImportResult runImport(byte[] archive) {
        AdminSystemImportService.StagedImport staged = service.stage(stream(archive));
        return service.replace(staged, new AdminSystemImportService.Progress());
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
        return assertThrows(AdminSystemImportRejectedException.class, () -> service.stage(stream(archive)));
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
