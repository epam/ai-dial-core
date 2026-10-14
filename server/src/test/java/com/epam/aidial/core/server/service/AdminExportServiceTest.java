package com.epam.aidial.core.server.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.jclouds.blobstore.domain.internal.BlobBuilderImpl;
import org.jclouds.blobstore.domain.internal.PageSetImpl;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.slf4j.LoggerFactory;
import redis.embedded.RedisServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.IntFunction;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
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

class AdminExportServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-02T03:04:05.678Z");
    private static final String CORE_VERSION = "1.2.3";

    private RedisServer redisServer;
    private RedissonClient redis;
    private BlobStorage blobStorage;
    private Path testDir;
    private LockService lockService;
    private ResourceService resourceService;
    private AdminExportService service;

    @BeforeEach
    void init() throws IOException {
        try {
            redisServer = RedisServer.newRedisServer()
                    .port(16382)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            redisServer.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16382");
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
            String serviceConfig = """
                    {
                     "maxSize" : 67108864,
                     "maxSizeToCache": 1048576,
                     "syncPeriod": 60000,
                     "syncDelay": 120000,
                     "syncBatch": 4096,
                     "cacheExpiration": 300000,
                     "compressionMinSize": 256,
                     "heartbeatPeriod": 60000
                    }
                    """;
            ResourceService.Settings settings = mapper.readValue(serviceConfig, ResourceService.Settings.class);
            resourceService = new ResourceService(mock(TimerService.class), redis, blobStorage, lockService, settings, null);
            service = new AdminExportService(blobStorage, resourceService, lockService, Clock.fixed(NOW, ZoneOffset.UTC), CORE_VERSION);
        } catch (Throwable e) {
            destroy();
            throw e;
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
    void testUnsyncedWriteIsFlushedAndGzipIsExportedAsStored() throws IOException {
        ResourceDescriptor model = new ResourceDescriptor(ResourceTypes.MODEL, "big", List.of(), "platform", "platform/", false);
        String body = "{\"endpoint\":\"" + "x".repeat(1000) + "\"}";
        resourceService.putResource(model, body, EtagHeader.ANY);

        Map<String, byte[]> entries = readEntries(export(service));

        String path = model.getAbsoluteFilePath();
        JsonNode metadata = json(entries.get(AdminExportService.METADATA_ENTRY_PREFIX + path));
        assertEquals("gzip", metadata.get("contentEncoding").asText());
        assertEquals("application/json", metadata.get("contentType").asText());
        assertTrue(metadata.get("userMetadata").size() > 0);
        assertEquals(body, new String(gunzip(entries.get(path)), StandardCharsets.UTF_8));
        assertEquals(body, resourceService.getResource(model));
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
        BlobStorage storage = mock(BlobStorage.class);
        stubList(storage, "public/", count, i -> "public/files/user1/f" + i);
        stubList(storage, "platform/", 0, i -> null);
        when(storage.load(anyString())).thenAnswer(invocation -> blob(invocation.getArgument(0), "c"));
        AdminExportService exportService = newService(storage, Clock.fixed(NOW, ZoneOffset.UTC));

        Path file = testDir.resolve("export.zip");
        try (OutputStream out = Files.newOutputStream(file)) {
            exportService.export(out);
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

        Map<String, byte[]> entries = readEntries(export(newService(storage, Clock.fixed(NOW, ZoneOffset.UTC))));

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
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        try (OutputStream gatedOut = new OutputStream() {
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
        }) {
            CompletableFuture<Void> exporting = CompletableFuture.runAsync(() -> service.export(gatedOut));
            assertTrue(writing.await(10, TimeUnit.SECONDS));

            AtomicBoolean acquired = new AtomicBoolean();
            CompletableFuture<Void> competing = CompletableFuture.runAsync(() ->
                    lockService.underBucketLocks(List.of("public/"), () -> {
                        acquired.set(true);
                        return null;
                    }));
            Thread.sleep(500);
            assertFalse(acquired.get());

            proceed.countDown();
            exporting.get(10, TimeUnit.SECONDS);
            competing.get(10, TimeUnit.SECONDS);
            assertTrue(acquired.get());
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

        UncheckedIOException e = assertThrows(UncheckedIOException.class, () -> service.export(failingOut));

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
    void testSlowExportLogsWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminExportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            Clock steppingClock = new Clock() {
                private Instant current = NOW;

                @Override
                public ZoneId getZone() {
                    return ZoneOffset.UTC;
                }

                @Override
                public Clock withZone(ZoneId zone) {
                    return this;
                }

                @Override
                public synchronized Instant instant() {
                    Instant result = current;
                    current = current.plus(LockService.LOCK_PERIOD).plusSeconds(1);
                    return result;
                }
            };

            export(new AdminExportService(blobStorage, resourceService, lockService, steppingClock, CORE_VERSION));

            assertEquals(1, appender.list.stream().filter(event -> event.getLevel() == Level.WARN).count());
            assertTrue(appender.list.stream().anyMatch(event -> event.getLevel() == Level.INFO
                    && event.getFormattedMessage().startsWith("Admin export completed")));
        } finally {
            logger.detachAppender(appender);
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
            assertThrows(UncheckedIOException.class, () -> service.export(failingOut));

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
    void testFastExportDoesNotLogWarning() {
        Logger logger = (Logger) LoggerFactory.getLogger(AdminExportService.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            export(service);

            assertTrue(appender.list.stream().noneMatch(event -> event.getLevel() == Level.WARN));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private AdminExportService newService(BlobStorage storage, Clock clock) {
        return new AdminExportService(storage, resourceService, lockService, clock, CORE_VERSION);
    }

    private void store(String path, String contentType, String contentEncoding, Map<String, String> userMetadata, String body) {
        blobStorage.store(path, contentType, contentEncoding, userMetadata, body.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] export(AdminExportService exportService) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.export(out);
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
        StorageMetadata metadata = mock(StorageMetadata.class);
        when(metadata.getType()).thenReturn(type);
        when(metadata.getName()).thenReturn(name);
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
}
