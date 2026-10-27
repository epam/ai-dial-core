package com.epam.aidial.core.server.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.service.LockService;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;
import redis.embedded.RedisServer;

import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class AdminExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05.678Z");
    private static final String CORE_VERSION = "1.2.3";

    private RedisServer redisServer;
    private RedissonClient redis;
    private BlobStorage blobStorage;
    private Path testDir;
    private LockService lockService;
    private AdminExportService service;

    @BeforeEach
    void init() throws IOException {
        try {
            int redisPort = freePort();
            redisServer = RedisServer.newRedisServer()
                    .port(redisPort)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            redisServer.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:" + redisPort);
            redis = Redisson.create(config);

            testDir = FileUtil.baseTestPath(AdminExportServiceTest.class);
            FileUtil.deleteDir(testDir);
            FileUtil.createDir(testDir.resolve("test"));
            String blobStorageConfig = """
                    {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "overrides": {
                          "jclouds.filesystem.basedir": "%s"
                        }
                      }
                    """.formatted(testDir.toString().replace('\\', '/'));
            ObjectMapper mapper = new ObjectMapper();
            blobStorage = new BlobStorage(mapper.readValue(blobStorageConfig, Storage.class));

            lockService = new LockService(redis, null);
            service = new AdminExportService(blobStorage, lockService, NOW::toEpochMilli, CORE_VERSION);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
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
            if (blobStorage != null) {
                blobStorage.close();
            }
        } finally {
            if (redisServer != null) {
                redisServer.stop();
            }
            if (testDir != null) {
                FileUtil.deleteDir(testDir);
            }
        }
    }

    @Test
    void testManifestIsFirstEntry() throws IOException {
        store("public/files/user1/a.txt", "text/plain", null, Map.of(), "a");

        Map<String, byte[]> entries = readEntries(export(service));

        assertEquals(AdminExportService.MANIFEST_ENTRY, entries.keySet().iterator().next());
        JsonNode manifest = json(entries.get(AdminExportService.MANIFEST_ENTRY));
        assertEquals(1, manifest.get("formatVersion").asInt());
        assertEquals(CORE_VERSION, manifest.get("coreVersion").asText());
        assertEquals("2026-01-02T03:04:05Z", manifest.get("createdAt").asText());
    }

    @Test
    void testContentIsExportedAsStoredWithMetadataBeforeContent() throws IOException {
        Map<String, String> userMetadata = Map.of("etag", "abc", "author", "user1");
        store("public/files/user1/a.txt", "text/plain", null, userMetadata, "hello");
        store("platform/misc/b.bin", "application/octet-stream", null, Map.of(), "bytes");

        Map<String, byte[]> entries = readEntries(export(service));

        assertArrayEquals("hello".getBytes(StandardCharsets.UTF_8), entries.get("public/files/user1/a.txt"));
        assertArrayEquals("bytes".getBytes(StandardCharsets.UTF_8), entries.get("platform/misc/b.bin"));

        JsonNode metadata = json(entries.get(AdminExportService.METADATA_ENTRY_PREFIX + "public/files/user1/a.txt"));
        assertEquals("text/plain", metadata.get("contentType").asText());
        assertFalse(metadata.has("contentEncoding"));
        assertEquals(userMetadata, ProxyUtil.MAPPER.convertValue(metadata.get("userMetadata"), Map.class));

        List<String> names = new ArrayList<>(entries.keySet());
        for (String path : List.of("public/files/user1/a.txt", "platform/misc/b.bin")) {
            assertEquals(names.indexOf(path) - 1, names.indexOf(AdminExportService.METADATA_ENTRY_PREFIX + path));
        }
    }

    @Test
    void testGzipIsExportedAsStored() throws IOException {
        String path = "platform/models/big";
        String body = "{\"endpoint\":\"" + "x".repeat(1000) + "\"}";
        byte[] gzipped = gzip(body);
        blobStorage.store(path, "application/json", "gzip", Map.of("etag", "abc"), gzipped);

        Map<String, byte[]> entries = readEntries(export(service));

        JsonNode metadata = json(entries.get(AdminExportService.METADATA_ENTRY_PREFIX + path));
        assertEquals("gzip", metadata.get("contentEncoding").asText());
        assertEquals("application/json", metadata.get("contentType").asText());
        assertEquals("abc", metadata.get("userMetadata").get("etag").asText());
        assertArrayEquals(gzipped, entries.get(path));
        assertEquals(body, new String(gunzip(entries.get(path)), StandardCharsets.UTF_8));
    }

    @Test
    void testBlobTimestampsAndContentHeadersAreExported() throws IOException {
        String path = "public/files/user1/a.txt";
        Blob blob = blob(path, "a");
        blob.getMetadata().setCreationDate(Date.from(Instant.parse("2025-03-04T05:06:07.123Z")));
        blob.getMetadata().setLastModified(Date.from(Instant.parse("2025-04-05T06:07:08.456Z")));
        blob.getMetadata().getContentMetadata().setContentType("text/plain");
        blob.getMetadata().getContentMetadata().setContentDisposition("attachment; filename=a.txt");
        blob.getMetadata().getContentMetadata().setContentLanguage("en");
        blob.getMetadata().getContentMetadata().setCacheControl("no-cache");
        BlobStorage storage = singleBlobStorage(path, blob);

        Map<String, byte[]> entries = readEntries(export(newService(storage)));

        JsonNode metadata = json(entries.get(AdminExportService.METADATA_ENTRY_PREFIX + path));
        assertEquals("2025-03-04T05:06:07.123Z", metadata.get("creationDate").asText());
        assertEquals("2025-04-05T06:07:08.456Z", metadata.get("lastModified").asText());
        assertEquals("attachment; filename=a.txt", metadata.get("contentDisposition").asText());
        assertEquals("en", metadata.get("contentLanguage").asText());
        assertEquals("no-cache", metadata.get("cacheControl").asText());
    }

    @Test
    void testEncodedContentIsNotDeflatedAgain() throws IOException {
        byte[] gzipped = gzip("x".repeat(10_000));
        blobStorage.store("public/files/user1/encoded", "application/json", "gzip", Map.of(), gzipped);
        blobStorage.store("public/files/user1/plain.txt", "text/plain", null, Map.of(), "x".repeat(10_000).getBytes(StandardCharsets.UTF_8));
        blobStorage.store("public/files/user1/identity.txt", "text/plain", "identity", Map.of(), "x".repeat(10_000).getBytes(StandardCharsets.UTF_8));
        Path file = testDir.resolve("compression.zip");

        try (OutputStream out = Files.newOutputStream(file)) {
            service.export(out, NOW);
        }

        try (ZipFile zip = new ZipFile(file.toFile())) {
            ZipEntry encoded = zip.getEntry("public/files/user1/encoded");
            ZipEntry plain = zip.getEntry("public/files/user1/plain.txt");
            ZipEntry identity = zip.getEntry("public/files/user1/identity.txt");
            assertEquals(gzipped.length, encoded.getSize());
            assertTrue(encoded.getCompressedSize() >= encoded.getSize(), "encoded content must not be compressed");
            assertTrue(plain.getCompressedSize() < plain.getSize() / 10, "plain content must be compressed");
            assertTrue(identity.getCompressedSize() < identity.getSize() / 10, "identity content must be compressed");
        }
    }

    @Test
    void testNamesAreRawUtf8() throws IOException {
        String path = "public/files/user1/my folder/файл ü.txt";
        store(path, "text/plain", null, Map.of(), "x");

        Map<String, byte[]> entries = readEntries(export(service));

        assertTrue(entries.containsKey(path), entries.keySet()::toString);
        assertTrue(entries.containsKey(AdminExportService.METADATA_ENTRY_PREFIX + path));
    }

    @Test
    void testEncryptionKeysAndCredentialsAreExcluded() throws IOException {
        store("public/encryption_keys/key1", "application/json", null, Map.of(), "secret");
        store("public/credentials/cred1", "application/json", null, Map.of(), "secret");
        store("platform/encryption_keys/key2", "application/json", null, Map.of(), "secret");
        store("platform/credentials/cred2", "application/json", null, Map.of(), "secret");
        store("public/files/owner/encryption_keys/notes.txt", "text/plain", null, Map.of(), "notes");
        store("public/files/owner/credentials/notes.txt", "text/plain", null, Map.of(), "notes");
        store("public/models/m1", "application/json", null, Map.of(), "{}");

        Map<String, byte[]> entries = readEntries(export(service));

        List<String> exported = entries.keySet().stream()
                .filter(name -> !name.startsWith(".dial-export/"))
                .toList();
        assertEquals(List.of("public/files/owner/credentials/notes.txt", "public/files/owner/encryption_keys/notes.txt", "public/models/m1"), exported);
        assertFalse(entries.keySet().stream().anyMatch(name -> name.contains("cred1") || name.contains("key1")));
    }

    @Test
    void testMarkersAreExportedWithMetadata() throws IOException {
        store("public/skills/folder/.dial-folder", "application/json", null, Map.of("k", "v"), "{}");
        store("public/skills/folder/skill/.dial-resource", "application/json", null, Map.of(), "{}");

        Map<String, byte[]> entries = readEntries(export(service));

        for (String path : List.of("public/skills/folder/.dial-folder", "public/skills/folder/skill/.dial-resource")) {
            assertTrue(entries.containsKey(path), entries.keySet()::toString);
            assertTrue(entries.containsKey(AdminExportService.METADATA_ENTRY_PREFIX + path));
        }
    }

    @Test
    void testAllBlobsAreExportedAcrossPages() throws IOException {
        int count = 1001;
        for (int i = 0; i < count; i++) {
            store("public/files/user1/f%04d.txt".formatted(i), "text/plain", null, Map.of(), "c" + i);
        }

        Map<String, byte[]> entries = readEntries(export(service));

        assertEquals(1 + 2 * count, entries.size());
        for (int i = 0; i < count; i++) {
            assertArrayEquals(("c" + i).getBytes(StandardCharsets.UTF_8), entries.get("public/files/user1/f%04d.txt".formatted(i)));
        }
    }

    @Test
    void testArchiveWithMoreThan65535EntriesIsReadable() throws IOException {
        int count = 33_000;
        BlobStorage storage = mock(BlobStorage.class, withSettings().stubOnly());
        stubList(storage, "public/", count, i -> "public/files/user1/f" + i);
        stubList(storage, "platform/", 0, i -> null);
        when(storage.load(anyString())).thenAnswer(invocation -> blob(invocation.getArgument(0), "c"));
        AdminExportService exportService = newService(storage);

        Path file = testDir.resolve("export.zip");
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file))) {
            exportService.export(out, NOW);
        }

        try (ZipFile zip = new ZipFile(file.toFile())) {
            assertEquals(1 + 2 * count, zip.size());
            ZipEntry last = zip.getEntry("public/files/user1/f" + (count - 1));
            assertNotNull(last);
            assertEquals("c", new String(zip.getInputStream(last).readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void testNonBlobEntriesAndBlobsDeletedMeanwhileAreSkipped() throws IOException {
        BlobStorage storage = mock(BlobStorage.class);
        StorageMetadata folder = storageMetadata(StorageType.FOLDER, "public/files/user1/dir");
        StorageMetadata kept = storageMetadata(StorageType.BLOB, "public/files/user1/kept.txt");
        StorageMetadata gone = storageMetadata(StorageType.BLOB, "public/files/user1/gone.txt");
        when(storage.list(eq("public/"), any(), anyInt(), eq(true)))
                .thenAnswer(invocation -> new PageSetImpl<>(List.of(folder, kept, gone), null));
        when(storage.list(eq("platform/"), any(), anyInt(), eq(true)))
                .thenAnswer(invocation -> new PageSetImpl<StorageMetadata>(List.of(), null));
        when(storage.load("public/files/user1/kept.txt")).thenReturn(blob("public/files/user1/kept.txt", "kept"));
        when(storage.load("public/files/user1/gone.txt")).thenReturn(null);

        Map<String, byte[]> entries = readEntries(export(newService(storage)));

        assertEquals(
                List.of(
                        AdminExportService.MANIFEST_ENTRY,
                        AdminExportService.METADATA_ENTRY_PREFIX + "public/files/user1/kept.txt",
                        "public/files/user1/kept.txt"
                ), new ArrayList<>(entries.keySet()));
        verify(storage, never()).load("public/files/user1/dir");
    }

    @Test
    void testConcurrentBucketLockWaitsForExport() throws Exception {
        store("public/files/user1/a.txt", "text/plain", null, Map.of(), "a");
        try (GatedOutputStream gatedOut = new GatedOutputStream()) {
            CompletableFuture<Void> exporting = CompletableFuture.runAsync(() -> service.export(gatedOut, NOW));
            assertTrue(gatedOut.writing.await(10, TimeUnit.SECONDS));

            AtomicBoolean acquired = new AtomicBoolean();
            CompletableFuture<Void> competing = CompletableFuture.runAsync(() ->
                    lockService.underBucketLocks(List.of("public/"), () -> {
                        acquired.set(true);
                        return null;
                    }));
            Thread.sleep(500);
            assertFalse(acquired.get());

            gatedOut.proceed.countDown();
            exporting.get(10, TimeUnit.SECONDS);
            competing.get(10, TimeUnit.SECONDS);
            assertTrue(acquired.get());
        }
    }

    @Test
    void testConcurrentExportIsRejected() throws Exception {
        store("public/files/user1/a.txt", "text/plain", null, Map.of(), "a");
        try (GatedOutputStream gatedOut = new GatedOutputStream()) {
            CompletableFuture<Void> exporting = CompletableFuture.runAsync(() -> service.export(gatedOut, NOW));
            assertTrue(gatedOut.writing.await(10, TimeUnit.SECONDS));

            ByteArrayOutputStream rejectedOut = new ByteArrayOutputStream();
            assertThrows(AdminExportBusyException.class, () -> service.export(rejectedOut, NOW));
            assertEquals(0, rejectedOut.size());

            gatedOut.proceed.countDown();
            exporting.get(10, TimeUnit.SECONDS);
        }

        assertFalse(readEntries(export(service)).isEmpty(), "the lock must be free after the export");
    }

    @Test
    void testBusyBucketLockRejectsExportAndReleasesTheOtherLock() {
        LockService.Lock held = lockService.tryLock("platform/");
        assertNotNull(held);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();

            assertThrows(AdminExportBusyException.class, () -> service.export(out, NOW));

            assertEquals(0, out.size());
            LockService.Lock publicLock = lockService.tryLock("public/");
            assertNotNull(publicLock, "public/ lock must be released after the rejected export");
            publicLock.close();
        } finally {
            held.close();
        }
    }

    @Test
    void testFailingOutputLeavesArchiveUnfinishedAndReleasesLocks() throws IOException {
        for (int i = 0; i < 20; i++) {
            store("public/files/user1/f" + i + ".txt", "text/plain", null, Map.of(), "content " + i);
        }
        ByteArrayOutputStream written = new ByteArrayOutputStream();
        OutputStream failingOut = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                if (written.size() > 300) {
                    throw new IOException("broken pipe");
                }
                written.write(b, off, len);
            }
        };

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> service.export(failingOut, NOW));

        assertEquals("broken pipe", e.getCause().getMessage());
        byte[] bytes = written.toByteArray();
        assertEquals(-1, indexOf(bytes, new byte[] {'P', 'K', 5, 6}), "end of central directory must not be written");
        for (String location : List.of("public/", "platform/")) {
            LockService.Lock lock = lockService.tryLock(location);
            assertNotNull(lock, location + " lock must be released");
            lock.close();
        }
    }

    @Test
    void testExportFailsOnceItRunsLongerThanTheLockPeriod() throws IOException {
        for (int i = 0; i < 5; i++) {
            store("public/files/user1/f" + i + ".txt", "text/plain", null, Map.of(), "content " + i);
        }
        AtomicLong current = new AtomicLong(NOW.toEpochMilli());
        ByteArrayOutputStream written = new ByteArrayOutputStream();

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () ->
                new AdminExportService(blobStorage, lockService, current::get, CORE_VERSION)
                        .export(afterFirstWrite(written, () -> current.addAndGet(LockService.LOCK_PERIOD.plusSeconds(1).toMillis())), NOW));

        assertTrue(e.getCause().getMessage().contains("lock period"), e.getCause().getMessage());
        assertEquals(-1, indexOf(written.toByteArray(), new byte[] {'P', 'K', 5, 6}), "end of central directory must not be written");
        for (String location : List.of("public/", "platform/")) {
            LockService.Lock lock = lockService.tryLock(location);
            assertNotNull(lock, location + " lock must be released");
            lock.close();
        }
    }

    @Test
    void testExportThatEndsExactlyAtTheLockPeriodSucceeds() throws IOException {
        store("public/files/user1/a.txt", "text/plain", null, Map.of(), "a");
        AtomicLong current = new AtomicLong(NOW.toEpochMilli());
        ByteArrayOutputStream written = new ByteArrayOutputStream();

        new AdminExportService(blobStorage, lockService, current::get, CORE_VERSION)
                .export(afterFirstWrite(written, () -> current.addAndGet(LockService.LOCK_PERIOD.toMillis())), NOW);

        assertTrue(readEntries(written.toByteArray()).containsKey("public/files/user1/a.txt"));
    }

    @Test
    void testStallTimeoutMustBePositiveAndBelowTheLockPeriod() {
        AdminExportService.Settings settings = new AdminExportService.Settings();
        assertDoesNotThrow(settings::validate);

        settings.setStallTimeout(LockService.LOCK_PERIOD.toMillis() - 1);
        assertDoesNotThrow(settings::validate);

        for (long invalid : new long[] {0, -1, LockService.LOCK_PERIOD.toMillis(), LockService.LOCK_PERIOD.toMillis() + 1}) {
            settings.setStallTimeout(invalid);
            assertThrows(IllegalArgumentException.class, settings::validate, "stallTimeout=" + invalid);
        }
    }

    @Test
    void testFailedExportLogsError() throws IOException {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminExportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (OutputStream failingOut = new OutputStream() {
            @Override
            public void write(int b) throws IOException {
                throw new IOException("broken pipe");
            }
        }) {
            assertThrows(UncheckedIOException.class, () -> service.export(failingOut, NOW));

            List<ILoggingEvent> errors = appender.list.stream().filter(event -> event.getLevel() == Level.ERROR).toList();
            assertEquals(1, errors.size());
            assertTrue(errors.get(0).getFormattedMessage().startsWith("Admin export failed"));
            assertNotNull(errors.get(0).getThrowableProxy());
            assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().startsWith("Admin export completed")));
        } finally {
            logger.detachAppender(appender);
        }
    }

    @Test
    void testDiscardReleasesDeflaterWithoutFinishingArchive() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AdminExportService.DiscardableZipOutputStream zip = new AdminExportService.DiscardableZipOutputStream(out);
        zip.putNextEntry(new ZipEntry("a"));
        zip.write(new byte[100]);

        zip.discard();
        zip.discard();

        assertThrows(NullPointerException.class, () -> zip.write(new byte[1024]), "the deflater must be ended");
        assertEquals(-1, indexOf(out.toByteArray(), new byte[] {'P', 'K', 5, 6}), "end of central directory must not be written");
    }

    private static OutputStream afterFirstWrite(ByteArrayOutputStream written, Runnable action) {
        return new OutputStream() {
            @Override
            public void write(int b) {
                write(new byte[] {(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) {
                boolean first = written.size() == 0;
                written.write(b, off, len);
                if (first) {
                    action.run();
                }
            }
        };
    }

    private AdminExportService newService(BlobStorage storage) {
        return new AdminExportService(storage, lockService, NOW::toEpochMilli, CORE_VERSION);
    }

    private static BlobStorage singleBlobStorage(String path, Blob blob) {
        BlobStorage storage = mock(BlobStorage.class);
        String bucketLocation = path.substring(0, path.indexOf('/') + 1);
        for (String location : List.of("public/", "platform/")) {
            List<StorageMetadata> items = location.equals(bucketLocation) ? List.of(storageMetadata(StorageType.BLOB, path)) : List.of();
            when(storage.list(eq(location), any(), anyInt(), eq(true))).thenAnswer(invocation -> new PageSetImpl<>(items, null));
        }
        when(storage.load(path)).thenReturn(blob);
        return storage;
    }

    private void store(String path, String contentType, String contentEncoding, Map<String, String> userMetadata, String body) {
        blobStorage.store(path, contentType, contentEncoding, userMetadata, body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] export(AdminExportService exportService) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.export(out, NOW);
        return out.toByteArray();
    }

    private static byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return out.toByteArray();
    }

    private static Map<String, byte[]> readEntries(byte[] archive) throws IOException {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }

    private static JsonNode json(byte[] bytes) throws IOException {
        return ProxyUtil.MAPPER.readTree(bytes);
    }

    private static byte[] gunzip(byte[] bytes) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            return in.readAllBytes();
        }
    }

    private static int indexOf(byte[] data, byte[] pattern) {
        for (int i = 0; i <= data.length - pattern.length; i++) {
            int j = 0;
            while (j < pattern.length && data[i + j] == pattern[j]) {
                j++;
            }
            if (j == pattern.length) {
                return i;
            }
        }
        return -1;
    }

    private static StorageMetadata storageMetadata(StorageType type, String name) {
        MutableStorageMetadataImpl metadata = new MutableStorageMetadataImpl();
        metadata.setType(type);
        metadata.setName(name);
        return metadata;
    }

    private static Blob blob(String path, String body) {
        return new BlobBuilderImpl().name(path).payload(body.getBytes(StandardCharsets.UTF_8)).build();
    }

    private static void stubList(BlobStorage storage, String bucketLocation, int count, IntFunction<String> pathOf) {
        int pageSize = 1000;
        List<StorageMetadata> all = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            all.add(storageMetadata(StorageType.BLOB, pathOf.apply(i)));
        }
        when(storage.list(eq(bucketLocation), any(), anyInt(), eq(true))).thenAnswer(invocation -> {
            String marker = invocation.getArgument(1);
            int from = marker == null ? 0 : Integer.parseInt(marker);
            int to = Math.min(from + pageSize, count);
            return new PageSetImpl<>(all.subList(from, to), to < count ? String.valueOf(to) : null);
        });
    }

    private static final class GatedOutputStream extends OutputStream {
        private final CountDownLatch writing = new CountDownLatch(1);
        private final CountDownLatch proceed = new CountDownLatch(1);

        @Override
        public void write(int b) throws IOException {
            gate();
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            gate();
        }

        private void gate() throws IOException {
            writing.countDown();
            try {
                proceed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
        }
    }
}
