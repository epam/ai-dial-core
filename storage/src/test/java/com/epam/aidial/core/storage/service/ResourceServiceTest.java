package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.blobstore.StorageProvider;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.data.UserMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagBuilder;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import org.apache.commons.lang3.tuple.Pair;
import org.jclouds.ContextBuilder;
import org.jclouds.blobstore.BlobStoreContext;
import org.jclouds.blobstore.domain.Blob;
import org.jclouds.blobstore.domain.MutableBlobMetadata;
import org.jclouds.io.MutableContentMetadata;
import org.jclouds.io.Payload;
import org.jclouds.io.payloads.ByteArrayPayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.AdditionalAnswers;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RTopic;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ResourceServiceTest {

    private RedisServer server;
    private RedissonClient client;
    private ResourceService service;
    private ResourceService.Settings settings;
    private BlobStorage storage;
    private BlobStorage realStorage;
    private Path testDir;

    @BeforeEach
    void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16371)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16371");

            client = Redisson.create(config);

            testDir = FileUtil.baseTestPath(ResourceServiceTest.class);
            FileUtil.deleteDir(testDir);
            FileUtil.createDir(testDir.resolve("test"));
            ObjectMapper mapper = new ObjectMapper();
            // the path must be JSON-encoded, otherwise a Windows path breaks parsing on its backslashes
            String blobStorageConfig = """
                    {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "prefix": "test-2",
                        "overrides": {
                          "jclouds.filesystem.basedir": %s
                        }
                      }
                    """.formatted(mapper.writeValueAsString(testDir.toString()));
            Storage storageConfig = mapper.readValue(blobStorageConfig, Storage.class);
            realStorage = new BlobStorage(storageConfig);
            storage = File.separatorChar == '\\' ? createStorageSpy(realStorage) : realStorage;

            TimerService timerService = Mockito.mock(TimerService.class);
            LockService lockService = new LockService(client, null);

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
            settings = mapper.readValue(serviceConfig, ResourceService.Settings.class);
            service = new ResourceService(timerService, client, storage, lockService, settings, null);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws IOException {
        try {
            if (client != null) {
                client.shutdown();
            }
            if (realStorage != null) {
                storage = realStorage;
                realStorage.close();
            }
        } finally {
            if (server != null) {
                server.stop();
            }
            FileUtil.deleteDir(testDir);
        }
    }

    @Test
    public void testListResources() {
        byte[] body = "1234567890".getBytes();
        ResourceDescriptor resourceDescriptor = new ResourceDescriptor(ResourceTypes.APPLICATION, null, List.of(), "public", "public/", true);
        String path = resourceDescriptor.getAbsoluteFilePath();
        for (int i = 0; i < 589; i++) {
            storage.store(path + "/" + "app_" + i, "application/octet-stream", null, Map.of("author", "user"), body);
        }
        List<Pair<ResourceItemMetadata, String>> result = service.listResources(resourceDescriptor, resourceFolderMetadata -> {
        });
        assertNotNull(result);
        assertEquals(589, result.size());
        TreeSet<Integer> ids = new TreeSet<>();
        for (var item : result) {
            String name = item.getKey().getName();
            assertEquals("user", item.getKey().getAuthor());
            int id = Integer.parseInt(name.substring(name.indexOf('_') + 1));
            ids.add(id);
        }
        assertEquals(589, ids.size());
        assertEquals(0, ids.getFirst());
        assertEquals(588, ids.getLast());

        // previous resources should be already in Redis
        // but these resources are missed
        for (int i = 589; i < 700; i++) {
            storage.store(path + "/" + "app_" + i, "application/octet-stream", null, Map.of("author", "user"), body);
        }

        result = service.listResources(resourceDescriptor, resourceFolderMetadata -> {
        });
        assertNotNull(result);
        assertEquals(700, result.size());

        ids = new TreeSet<>();
        for (var item : result) {
            String name = item.getKey().getName();
            assertEquals("user", item.getKey().getAuthor());
            int id = Integer.parseInt(name.substring(name.indexOf('_') + 1));
            ids.add(id);
        }
        assertEquals(700, ids.size());
        assertEquals(0, ids.getFirst());
        assertEquals(699, ids.getLast());
    }


    @Test
    public void testEncode() {
        assertNull(ResourceService.encode(null));
        assertEquals("", ResourceService.encode(""));
        assertEquals("base58_24SWdWXor", ResourceService.encode("{abñ}"));
    }

    @Test
    public void testDecode() {
        assertNull(ResourceService.decode(null));
        assertEquals("", ResourceService.decode(""));
        assertEquals("{abc}", ResourceService.decode("{abc}"));
        assertEquals("{abñ}", ResourceService.decode("base58_24SWdWXor"));
    }

    @Test
    public void testCleanupTempFolder() throws Exception {
        byte[] body = "1234567890".getBytes();
        DateTimeFormatter formatter = TempFolderCleaner.FOLDER_FORMATTER;
        LocalDateTime now = LocalDateTime.now();

        String expiredFolder = now.minusHours(3).format(formatter);
        String recentFolder = now.minusMinutes(30).format(formatter);
        String currentFolder = now.format(formatter);

        String expiredFile = ResourceService.TEMP_FOLDER + "/" + expiredFolder + "/expired";
        String recentFile = ResourceService.TEMP_FOLDER + "/" + recentFolder + "/recent";
        String currentFile = ResourceService.TEMP_FOLDER + "/" + currentFolder + "/current";

        // Real staging timestamps contain colons, which cannot be represented by a Windows filesystem folder.
        try (BlobStoreContext context = ContextBuilder.newBuilder("transient").buildView(BlobStoreContext.class)) {
            context.getBlobStore().createContainerInLocation(null, "test");
            BlobStorage temporaryStorage = Mockito.spy(realStorage);
            Field field = BlobStorage.class.getDeclaredField("blobStore");
            field.setAccessible(true);
            field.set(temporaryStorage, context.getBlobStore());
            ResourceService temporaryService = serviceWithBlobStorage(temporaryStorage, "cleanup-temp");
            temporaryStorage.store(expiredFile, "application/octet-stream", null, Map.of(), body);
            temporaryStorage.store(recentFile, "application/octet-stream", null, Map.of(), body);
            temporaryStorage.store(currentFile, "application/octet-stream", null, Map.of(), body);

            temporaryService.cleanupTempFolder();

            assertFalse(temporaryStorage.exists(expiredFile));
            // A file uploaded within the last hour is kept because its folder hour may still be in progress.
            assertTrue(temporaryStorage.exists(recentFile));
            assertTrue(temporaryStorage.exists(currentFile));
        }
    }

    @Test
    public void testLoadEmpty() {
        assertEquals(Map.of(), load(service, List.of()));
    }

    /**
     * 120 items span three Redis pipelines, so this covers the chunking loop.
     */
    @Test
    public void testLoadAcrossMultipleChunks() {
        List<ResourceDescriptor> descriptors = new ArrayList<>();
        for (int i = 0; i < 120; i++) {
            ResourceDescriptor descriptor = resource("chunked_" + i);
            service.putResource(descriptor, "body_" + i, EtagHeader.NEW_ONLY);
            descriptors.add(descriptor);
        }

        Map<ResourceDescriptor, String> result = load(service, descriptors);

        assertEquals(120, result.size());
        for (int i = 0; i < 120; i++) {
            assertEquals("body_" + i, result.get(descriptors.get(i)));
        }
    }

    @Test
    public void testLoadOmitsMissingAndDeleted() {
        ResourceDescriptor existing = resource("existing");
        ResourceDescriptor deleted = resource("deleted");
        ResourceDescriptor neverCreated = resource("never_created");
        service.putResource(existing, "body", EtagHeader.NEW_ONLY);
        service.putResource(deleted, "body", EtagHeader.NEW_ONLY);
        assertTrue(service.deleteResource(deleted, EtagHeader.ANY));

        Map<ResourceDescriptor, String> result = load(service, List.of(existing, deleted, neverCreated));

        assertEquals(Map.of(existing, "body"), result);
    }

    /**
     * A delete leaves a tombstone in Redis, so the item must be answered from the cache without a blob
     * call - that is the whole point of distinguishing a cache miss from a cached absence. A tombstone
     * also carries no body, so returning it would fail on decoding.
     */
    @Test
    public void testLoadSkipsBlobCallForResourceCachedAsAbsent() {
        BlobStorage spy = createStorageSpy(realStorage);
        ResourceService spied = serviceWithBlobStorage(spy, "cached-absent");

        ResourceDescriptor deleted = resource("cached_absent");
        spied.putResource(deleted, "body", EtagHeader.NEW_ONLY);
        assertTrue(spied.deleteResource(deleted, EtagHeader.ANY));
        Mockito.clearInvocations(spy);

        assertEquals(Map.of(), load(spied, List.of(deleted)));
        Mockito.verify(spy, Mockito.never()).load(Mockito.anyString());
    }

    /**
     * Resources present in blob storage but absent from Redis: every item is a cache miss, so this drives
     * the blob fallback across more than one chunk.
     */
    @Test
    public void testLoadFallsBackToBlobStorage() {
        List<ResourceDescriptor> descriptors = new ArrayList<>();
        for (int i = 0; i < 60; i++) {
            ResourceDescriptor descriptor = resource("blob_only_" + i);
            storage.store(descriptor.getAbsoluteFilePath(), "application/json", null,
                    Map.of("author", "user"), ("body_" + i).getBytes());
            descriptors.add(descriptor);
        }

        Map<ResourceDescriptor, String> result = load(service, descriptors);

        assertEquals(60, result.size());
        for (int i = 0; i < 60; i++) {
            assertEquals("body_" + i, result.get(descriptors.get(i)));
        }
    }

    /**
     * A failing blob read must surface rather than be swallowed into a partial result. It arrives wrapped,
     * as it always has on this path - the bulk read deliberately does not cancel its siblings, because
     * interrupting an in-flight blob read can leave the underlying handle open.
     */
    @Test
    public void testLoadPropagatesBlobFailure() {
        BlobStorage failing = Mockito.mock(BlobStorage.class);
        Mockito.when(failing.load(Mockito.anyString())).thenThrow(new IllegalStateException("blob is down"));
        ResourceService broken = serviceWithBlobStorage(failing, "failing-blob");

        ExecutionException error = assertThrows(ExecutionException.class,
                () -> load(broken, List.of(resource("unreadable"))));
        // wrapped once per future the read passed through - the chunk task and the per-resource fallback
        Throwable root = error;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        assertEquals("blob is down", root.getMessage());
    }

    /**
     * The blob store's own metadata may not report a content length (observed in production
     * against GCS: https://github.com/epam/ai-dial-core/issues/1971). getResourceMetadata must
     * not NPE when unboxing it into FileMetadata's primitive field.
     */
    @Test
    public void testGetResourceMetadataToleratesNullContentLengthFromBlobMetadata() {
        ResourceDescriptor descriptor = fileResource("null_content_length");
        storage.store(descriptor.getAbsoluteFilePath(), "application/octet-stream", null,
                Map.of("author", "user"), "body".getBytes());

        BlobStorage spy = blobStorageWithNullContentLength(descriptor.getAbsoluteFilePath());
        ResourceService spied = serviceWithBlobStorage(spy, "null-content-length-meta");

        ResourceItemMetadata metadata = spied.getResourceMetadata(descriptor);

        assertNotNull(metadata);
        assertEquals(0L, ((FileMetadata) metadata).getContentLength());
    }

    /**
     * Same null-content-length hazard, but on the getResourceStream path, which reads the blob's
     * metadata directly rather than through getResourceMetadata.
     */
    @Test
    public void testGetResourceStreamToleratesNullContentLengthFromBlobMetadata() throws IOException {
        ResourceDescriptor descriptor = fileResource("null_content_length_stream");
        storage.store(descriptor.getAbsoluteFilePath(), "application/octet-stream", null,
                Map.of("author", "user"), "body".getBytes());

        BlobStorage spy = blobStorageWithNullContentLength(descriptor.getAbsoluteFilePath());
        ResourceService spied = serviceWithBlobStorage(spy, "null-content-length-stream");

        try (ResourceService.ResourceStream stream = spied.getResourceStream(descriptor, EtagHeader.ANY)) {
            assertNotNull(stream);
            assertEquals(4L, stream.contentLength());
        }
    }

    @Test
    void testCopyResourceOrdersRecordKeysAndClosesLocksOnFailure() {
        ResourceDescriptor source = fileResource("z-source");
        ResourceDescriptor destination = fileResource("a-destination");
        LockService lockService = Mockito.mock(LockService.class);
        LockService.Lock sourceLock = Mockito.mock(LockService.Lock.class);
        Mockito.when(lockService.lock("file:bucket/files/z-source")).thenReturn(sourceLock);
        Mockito.when(lockService.lock("file:bucket/files/a-destination")).thenThrow(new IllegalStateException("second lock failed"));
        ResourceService target = new ResourceService(Mockito.mock(TimerService.class), client, storage, lockService, settings, null);
        assertThrows(IllegalStateException.class, () -> target.copyResource(source, destination));
        InOrder order = Mockito.inOrder(lockService, sourceLock);
        order.verify(lockService).lock("file:bucket/files/z-source");
        order.verify(lockService).lock("file:bucket/files/a-destination");
        order.verify(sourceLock).close();
    }

    @Test
    @Timeout(10)
    void testLockedMovePreservesConcurrentPlainReplacement() throws Exception {
        ResourceDescriptor source = fileResource("concurrent-source");
        ResourceDescriptor destination = fileResource("concurrent-destination");
        service.putFile(source, "original".getBytes(), EtagHeader.ANY, "text/plain", "author");
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<FileMetadata> writer;
        try (var sourceLock = service.lockResource(source); var destinationLock = service.lockResource(destination)) {
            writer = CompletableFuture.supplyAsync(() -> {
                started.countDown();
                return service.putFile(source, "replacement".getBytes(), EtagHeader.ANY, "text/plain", "author");
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertFalse(writer.isDone());
            assertTrue(service.copyResourceLocked(source, destination, null, true));
            assertTrue(service.deleteResourceLocked(source, EtagHeader.ANY));
        }
        assertNotNull(writer.get(5, TimeUnit.SECONDS));
        assertEquals("replacement", new String(service.getResourceBytes(source)));
        assertEquals("original", new String(service.getResourceBytes(destination)));
    }

    @Test
    @Timeout(10)
    void testCopyResourceLockedPreservesMetadataWithoutReentry() {
        ResourceDescriptor source = fileResource("locked-source");
        ResourceDescriptor destination = fileResource("locked-destination");
        FileMetadata original = service.putFile(source, "source".getBytes(), EtagHeader.ANY, "application/x-custom", "author");
        service.putFile(destination, "old".getBytes(), EtagHeader.ANY, "text/plain", "other");
        try (var sourceLock = service.lockResource(source); var destinationLock = service.lockResource(destination)) {
            assertFalse(service.copyResourceLocked(source, destination, null, false));
            assertTrue(service.copyResourceLocked(source, destination, null, true));
            assertEquals("source", new String(service.getResourceBytes(destination, EtagHeader.ANY, false)));
            FileMetadata copied = (FileMetadata) service.getResourceMetadata(destination);
            assertEquals(original.getEtag(), copied.getEtag());
            assertEquals(original.getAuthor(), copied.getAuthor());
            assertEquals(original.getCreatedAt(), copied.getCreatedAt());
            assertEquals(original.getUpdatedAt(), copied.getUpdatedAt());
            assertEquals(original.getContentType(), copied.getContentType());
        }
        assertFalse(service.copyResourceLocked(fileResource("absent"), destination, null, true));
        assertFalse(service.copyResourceLocked(source, source, null, false));
        assertTrue(service.copyResourceLocked(source, source, null, true));
    }

    @Test
    void testCopyResourceLockedOverridesUserMetadata() {
        ResourceDescriptor source = fileResource("metadata-source");
        ResourceDescriptor destination = fileResource("metadata-destination");
        service.putFile(source, "body".getBytes(), EtagHeader.ANY, "application/x-custom", "original");
        UserMetadata userMetadata = new UserMetadata();
        userMetadata.setAuthor("copier");
        userMetadata.setCreatedAt(100L);
        userMetadata.setUpdatedAt(200L);
        userMetadata.setEtag("copied-etag");
        userMetadata.setResourceType(ResourceTypes.FILE.name());
        assertTrue(service.copyResourceLocked(source, destination, userMetadata, false));
        FileMetadata copied = (FileMetadata) service.getResourceMetadata(destination);
        assertEquals("copier", copied.getAuthor());
        assertEquals(100L, copied.getCreatedAt());
        assertEquals(200L, copied.getUpdatedAt());
        assertEquals("\"copied-etag\"", copied.getEtag());
        assertEquals("application/x-custom", copied.getContentType());
    }

    @Test
    void testCopyResourceLockedInvalidatesDestinationCacheAfterBlobCopy() {
        ResourceDescriptor source = fileResource("cache-source");
        ResourceDescriptor destination = fileResource("cache-destination");
        service.putFile(source, "new".getBytes(), EtagHeader.ANY, "application/x-new", "author");
        service.putFile(destination, "old".getBytes(), EtagHeader.ANY, "application/x-old", "author");
        BlobStorage spy = createStorageSpy(realStorage);
        ResourceService target = serviceWithBlobStorage(spy, "copy-cache");
        Mockito.doAnswer(invocation -> {
            // A reader can fill a cache record while a provider-side copy is in flight.
            assertEquals("old", new String(target.getResourceBytes(destination, EtagHeader.ANY, false)));
            return FileUtil.retryFilesystemCall(() -> callRealStorage(invocation));
        }).when(spy).copy(Mockito.eq(source.getAbsoluteFilePath()), Mockito.eq(destination.getAbsoluteFilePath()), Mockito.isNull());
        service.copyResource(source, fileResource("sync-source"));
        service.copyResource(destination, fileResource("sync-destination"));
        assertEquals("old", new String(target.getResourceBytes(destination)));
        assertTrue(target.copyResourceLocked(source, destination, null, true));
        assertEquals("new", new String(target.getResourceBytes(destination)));
        assertEquals("application/x-new", ((FileMetadata) target.getResourceMetadata(destination)).getContentType());
    }

    @Test
    @Timeout(10)
    void testDeleteResourceLockedProviderFailurePreservesSource() {
        ResourceDescriptor source = fileResource("delete-failed-source");
        ResourceDescriptor destination = fileResource("delete-failed-destination");
        service.putFile(source, "source".getBytes(), EtagHeader.ANY, "application/x-custom", "author");
        service.copyResource(source, fileResource("flush-delete-source"));
        BlobStorage failingStorage = createStorageSpy(realStorage);
        Mockito.doThrow(new IllegalStateException("provider delete failed")).when(failingStorage).delete(source.getAbsoluteFilePath());
        ResourceService target = serviceWithBlobStorage(failingStorage, "delete-failure");
        assertEquals("source", new String(target.getResourceBytes(source)));
        try (var sourceLock = target.lockResource(source); var destinationLock = target.lockResource(destination)) {
            assertTrue(target.copyResourceLocked(source, destination, null, true));
            assertThrows(IllegalStateException.class, () -> target.deleteResourceLocked(source, EtagHeader.ANY));
            assertEquals("source", new String(target.getResourceBytes(source, EtagHeader.ANY, false)));
            assertEquals("source", new String(target.getResourceBytes(destination, EtagHeader.ANY, false)));
            assertNotNull(failingStorage.load(source.getAbsoluteFilePath()));
        }
        ResourceService restored = serviceWithBlobStorage(storage, "delete-failure");
        try (var ignored = restored.lockResource(source)) {
            assertTrue(restored.deleteResourceLocked(source, EtagHeader.ANY));
            assertNull(restored.getResourceBytes(source, EtagHeader.ANY, false));
            assertFalse(restored.deleteResourceLocked(source, EtagHeader.ANY));
            assertFalse(storage.exists(source.getAbsoluteFilePath()));
        }
    }

    @Test
    @Timeout(10)
    void testDeleteResourceLockedQuietFilesystemFailureDoesNotTombstoneSource() {
        ResourceDescriptor source = fileResource("quiet-fs-source");
        FileMetadata original = service.putFile(source, "source".getBytes(), EtagHeader.ANY, "application/x-custom", "author");
        service.copyResource(source, fileResource("flush-quiet-fs-source"));
        BlobStorage quietStorage = Mockito.spy(realStorage);
        // A normally-returning provider delete leaves a real filesystem blob behind.
        Mockito.doNothing().when(quietStorage).delete(source.getAbsoluteFilePath());
        ResourceService target = serviceWithBlobStorage(quietStorage, "quiet-fs-source");
        assertEquals(StorageProvider.FILESYSTEM, quietStorage.getStorageProvider());
        assertEquals("source", new String(target.getResourceBytes(source)));
        try (var ignored = target.lockResource(source)) {
            IllegalStateException failure = assertThrows(IllegalStateException.class, () -> target.deleteResourceLocked(source, EtagHeader.ANY));
            assertTrue(failure.getMessage().contains(source.getUrl()));
            assertEquals("source", new String(target.getResourceBytes(source, EtagHeader.ANY, false)));
            assertEquals(original.getEtag(), target.getResourceMetadata(source).getEtag());
            assertTrue(realStorage.exists(source.getAbsoluteFilePath()));
        }
        Mockito.verify(quietStorage).exists(source.getAbsoluteFilePath());
    }

    @Test
    @Timeout(10)
    void testDeleteResourceLockedDoesNotCheckCloudExistence() {
        ResourceDescriptor source = fileResource("cloud-delete-source");
        service.putFile(source, "source".getBytes(), EtagHeader.ANY, "text/plain", "author");
        BlobStorage cloudStorage = Mockito.spy(realStorage);
        Mockito.doReturn(StorageProvider.AWS_S3).when(cloudStorage).getStorageProvider();
        ResourceService target = serviceWithBlobStorage(cloudStorage, null);
        try (var ignored = target.lockResource(source)) {
            assertTrue(target.deleteResourceLocked(source, EtagHeader.ANY));
            assertNull(target.getResourceBytes(source, EtagHeader.ANY, false));
        }
        Mockito.verify(cloudStorage, Mockito.never()).exists(Mockito.anyString());
        assertFalse(realStorage.exists(source.getAbsoluteFilePath()));
    }

    @Test
    @Timeout(10)
    void testDeleteResourceLockedRedisFailureCanRestoreMetadataFromDestination() {
        for (boolean failSync : List.of(false, true)) {
            ResourceDescriptor source = fileResource("redis-delete-source-" + failSync);
            ResourceDescriptor backup = fileResource("redis-delete-backup-" + failSync);
            FileMetadata original = service.putFile(source, "source".getBytes(), EtagHeader.ANY, "application/x-custom", "original author");
            assertTrue(service.copyResource(source, backup));
            RedissonClient failingRedis = Mockito.mock(RedissonClient.class, AdditionalAnswers.delegatesTo(client));
            String sourceKey = "file:" + source.getAbsoluteFilePath();
            AtomicBoolean failed = new AtomicBoolean();
            Mockito.doAnswer(invocation -> {
                Codec codec = invocation.getArgument(1);
                RMap<String, byte[]> map = Mockito.spy(client.<String, byte[]>getMap(sourceKey, codec));
                Answer<Object> failAfterWrite = write -> {
                    Object result = write.callRealMethod();
                    if (failed.compareAndSet(false, true)) {
                        throw new RedisException("lost source deletion acknowledgement");
                    }
                    return result;
                };
                if (failSync) {
                    Mockito.doAnswer(failAfterWrite).when(map).put(Mockito.eq("synced"), Mockito.any(byte[].class));
                } else {
                    Mockito.doAnswer(failAfterWrite).when(map).putAll(Mockito.anyMap());
                }
                return map;
            }).when(failingRedis).getMap(Mockito.eq(sourceKey), Mockito.any(Codec.class));
            ResourceService target = new ResourceService(Mockito.mock(TimerService.class), failingRedis, storage,
                    new LockService(client, null), settings, null);
            try (var sourceLock = target.lockResource(source); var backupLock = target.lockResource(backup)) {
                assertThrows(RedisException.class, () -> target.deleteResourceLocked(source, EtagHeader.ANY));
                assertNull(storage.meta(source.getAbsoluteFilePath()), "provider deletion completed before the Redis failure");
                UserMetadata metadata = new UserMetadata();
                metadata.setAuthor(original.getAuthor());
                metadata.setCreatedAt(original.getCreatedAt());
                metadata.setUpdatedAt(original.getUpdatedAt());
                metadata.setEtag(original.getEtag());
                metadata.setResourceType(ResourceTypes.FILE.name());
                assertTrue(target.copyResourceLocked(backup, source, metadata, true));
                assertEquals("source", new String(target.getResourceBytes(source, EtagHeader.ANY, false)));
                assertEquals("source", new String(target.getResourceBytes(backup, EtagHeader.ANY, false)));
                FileMetadata restored = (FileMetadata) target.getResourceMetadata(source);
                assertEquals(original.getEtag(), restored.getEtag());
                assertEquals(original.getAuthor(), restored.getAuthor());
                assertEquals(original.getCreatedAt(), restored.getCreatedAt());
                assertEquals(original.getUpdatedAt(), restored.getUpdatedAt());
                assertEquals(original.getContentType(), restored.getContentType());
            }
        }
    }

    @Test
    void testDeleteResourceLockedEventFailureDoesNotUndoCommittedDeletion() throws Exception {
        ResourceDescriptor source = fileResource("event-delete-source");
        service.putFile(source, "source".getBytes(), EtagHeader.ANY, "text/plain", "author");
        Field field = ResourceTopic.class.getDeclaredField("topic");
        field.setAccessible(true);
        RTopic topic = Mockito.spy((RTopic) field.get(service.getTopic()));
        Mockito.doThrow(new RedisException("event broker unavailable")).when(topic).publish(Mockito.any());
        field.set(service.getTopic(), topic);
        try (var ignored = service.lockResource(source)) {
            assertTrue(service.deleteResourceLocked(source, EtagHeader.ANY));
            assertNull(service.getResourceBytes(source, EtagHeader.ANY, false));
            assertNull(storage.meta(source.getAbsoluteFilePath()));
        }
    }

    @Test
    void testDeleteResourceLockedValidatesConditionsBeforeProviderDeletion() {
        ResourceDescriptor source = fileResource("delete-condition");
        service.putFile(source, "source".getBytes(), EtagHeader.ANY, "text/plain", "author");
        service.copyResource(source, fileResource("flush-delete-condition"));
        BlobStorage spy = createStorageSpy(realStorage);
        ResourceService target = serviceWithBlobStorage(spy, "delete-condition");
        assertThrows(HttpException.class, () -> target.deleteResourceLocked(source, EtagHeader.fromHeader("wrong", null, "DELETE")));
        Mockito.verify(spy, Mockito.never()).delete(source.getAbsoluteFilePath());
        assertEquals("source", new String(target.getResourceBytes(source)));
    }

    @Test
    @Timeout(10)
    void testGetResourceBytesAlreadyLockedUsesConditions() {
        ResourceDescriptor source = fileResource("locked-read");
        FileMetadata metadata = service.putFile(source, "body".getBytes(), EtagHeader.ANY, "text/plain", "author");
        service.copyResource(source, fileResource("flush-read"));
        try (var ignored = service.lockResource(source)) {
            assertEquals("body", new String(service.getResourceBytes(source, EtagHeader.fromHeader(metadata.getEtag(), null, "GET"), false)));
            assertThrows(HttpException.class, () -> service.getResourceBytes(source, EtagHeader.fromHeader("wrong", null, "GET"), false));
            assertNull(service.getResourceBytes(fileResource("absent-read"), EtagHeader.ANY, false));
        }
    }

    @Test
    @Timeout(10)
    void testLockedReadsUseMasterRatherThanReplicaWithoutReentry() throws Exception {
        ResourceDescriptor source = fileResource("replica-read-source");
        FileMetadata old = service.putFile(source, "x".getBytes(), EtagHeader.ANY, "text/plain", "author");
        Map<String, byte[]> replicaFields = captureRedisFields(source);
        FileMetadata current = service.putFile(source, "four".getBytes(), EtagHeader.ANY, "text/plain", "author");
        ResourceService target = serviceWithReplicaView(source, replicaFields);
        assertEquals(old.getEtag(), target.getResourceMetadata(source).getEtag());
        assertEquals("x", new String(target.getResourceBytes(source)));
        try (var ignored = target.lockResource(source)) {
            FileMetadata metadata = (FileMetadata) target.getResourceMetadataLocked(source);
            assertEquals(current.getEtag(), metadata.getEtag());
            assertEquals(4, metadata.getContentLength());
            assertEquals("four", new String(target.getResourceBytesLocked(source, EtagHeader.ANY)));
            assertEquals("four", target.getResourceLocked(source, EtagHeader.ANY));
            assertThrows(HttpException.class, () -> target.getResourceBytesLocked(source, EtagHeader.fromHeader(old.getEtag(), null, "GET")));
            try (var stream = target.getResourceStreamLocked(source, EtagHeader.ANY)) {
                assertNotNull(stream);
                assertEquals(current.getEtag(), stream.etag());
                assertEquals("four", new String(stream.inputStream().readAllBytes()));
            }
        }
    }

    @Test
    @Timeout(10)
    void testLockedCopyUsesActualMasterMetadataAndBytes() {
        ResourceDescriptor source = fileResource("replica-copy-source");
        ResourceDescriptor destination = fileResource("replica-copy-destination");
        service.putFile(source, "x".getBytes(), EtagHeader.ANY, "text/plain", "author");
        Map<String, byte[]> replicaFields = captureRedisFields(source);
        FileMetadata current = service.putFile(source, "four".getBytes(), EtagHeader.ANY, "application/x-current", "author");
        ResourceService target = serviceWithReplicaView(source, replicaFields);
        try (var sourceLock = target.lockResource(source); var destinationLock = target.lockResource(destination)) {
            assertTrue(target.copyResourceLocked(source, destination, null, false));
            FileMetadata copied = (FileMetadata) target.getResourceMetadataLocked(destination);
            assertEquals(current.getEtag(), copied.getEtag());
            assertEquals(4, copied.getContentLength());
            assertEquals(current.getContentType(), copied.getContentType());
            assertEquals(EtagBuilder.generateEtag("four".getBytes()), copied.getEtag());
            assertEquals("four", new String(target.getResourceBytesLocked(destination, EtagHeader.ANY)));
        }
    }

    @Test
    @Timeout(10)
    void testLockedReadMissesConditionsAndStreamingBranches() throws Exception {
        ResourceDescriptor absent = fileResource("locked-absent");
        try (var ignored = service.lockResource(absent)) {
            assertNull(service.getResourceMetadataLocked(absent));
            assertNull(service.getResourceBytesLocked(absent, EtagHeader.ANY));
            assertNull(service.getResourceLocked(absent, EtagHeader.ANY));
            assertNull(service.getResourceStreamLocked(absent, EtagHeader.ANY));
            assertThrows(HttpException.class, () -> service.getResourceBytesLocked(absent, EtagHeader.fromHeader("*", null, "GET")));
        }
        ResourceDescriptor folder = new ResourceDescriptor(ResourceTypes.FILE, "folder", List.of(), "bucket", "bucket/", true);
        assertThrows(IllegalArgumentException.class, () -> service.getResourceMetadataLocked(folder));
        assertThrows(IllegalArgumentException.class, () -> service.getResourceStreamLocked(resource("compressed"), EtagHeader.ANY));
        settings.setMaxSizeToCache(4);
        ResourceService target = serviceWithBlobStorage(storage, "locked-stream");
        for (String body : List.of("abc", "abcdefgh")) {
            ResourceDescriptor descriptor = fileResource("stream-" + body.length());
            storage.store(descriptor.getAbsoluteFilePath(), "text/plain", null, Map.of("etag", EtagBuilder.generateEtag(body.getBytes())), body.getBytes());
            try (var ignored = target.lockResource(descriptor); var stream = target.getResourceStreamLocked(descriptor, EtagHeader.ANY)) {
                assertNotNull(stream);
                assertEquals(body.length(), stream.contentLength());
                assertEquals(body, new String(stream.inputStream().readAllBytes()));
            }
        }
        ResourceDescriptor bytes = fileResource("locked-byte-miss");
        storage.store(bytes.getAbsoluteFilePath(), "text/plain", null, Map.of(), "body".getBytes());
        try (var ignored = target.lockResource(bytes)) {
            assertEquals("body", new String(target.getResourceBytesLocked(bytes, EtagHeader.ANY)));
        }
    }

    private Map<String, byte[]> captureRedisFields(ResourceDescriptor descriptor) {
        return new HashMap<>(client.<String, byte[]>getMap(RedisUtil.redisKey(descriptor, null),
                new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE)).readAllMap());
    }

    private ResourceService serviceWithReplicaView(ResourceDescriptor descriptor, Map<String, byte[]> replicaFields) {
        RedissonClient replicaRedis = Mockito.mock(RedissonClient.class, AdditionalAnswers.delegatesTo(client));
        String key = RedisUtil.redisKey(descriptor, null);
        Mockito.doAnswer(invocation -> {
            Codec codec = invocation.getArgument(1);
            RMap<String, byte[]> map = Mockito.spy(client.<String, byte[]>getMap(key, codec));
            Mockito.doAnswer(read -> {
                Set<String> requested = read.getArgument(0);
                Map<String, byte[]> fields = new HashMap<>();
                requested.forEach(name -> {
                    if (replicaFields.containsKey(name)) {
                        fields.put(name, replicaFields.get(name));
                    }
                });
                return fields;
            }).when(map).getAll(Mockito.anySet());
            return map;
        }).when(replicaRedis).getMap(Mockito.eq(key), Mockito.any(Codec.class));
        return new ResourceService(Mockito.mock(TimerService.class), replicaRedis, storage, new LockService(client, null), settings, null);
    }

    private static BlobStorage createStorageSpy(BlobStorage original) {
        BlobStorage storage = Mockito.spy(original);
        if (File.separatorChar == '\\') {
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorage(invocation))).when(storage)
                    .store(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(byte[].class));
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> callRealStorage(invocation))).when(storage)
                    .copy(Mockito.anyString(), Mockito.anyString(), Mockito.any());
            // JClouds filesystem removeBlob logs an IOException instead of returning it to the caller.
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> {
                callRealStorage(invocation);
                String path = invocation.getArgument(0);
                if (original.exists(path)) {
                    throw new FileSystemException(path, null, "Filesystem provider left the deleted blob in place");
                }
                return null;
            })).when(storage).delete(Mockito.anyString());
        }
        return storage;
    }

    @SneakyThrows
    private static Object callRealStorage(InvocationOnMock invocation) {
        return invocation.callRealMethod();
    }

    @SneakyThrows
    private BlobStorage blobStorageWithNullContentLength(String blobKey) {
        Blob realBlob = storage.load(blobKey);
        // Mockito's retained thenReturn blob must not retain a file-backed payload after this fixture owns it.
        try (Payload payload = realBlob.getPayload(); InputStream stream = payload.openStream()) {
            ByteArrayPayload copied = new ByteArrayPayload(stream.readAllBytes());
            copied.setContentMetadata(payload.getContentMetadata());
            realBlob.setPayload(copied);
        }
        MutableBlobMetadata spiedMeta = Mockito.spy(realBlob.getMetadata());
        MutableContentMetadata spiedContent = Mockito.spy(spiedMeta.getContentMetadata());
        Mockito.when(spiedContent.getContentLength()).thenReturn(null);
        Mockito.when(spiedMeta.getContentMetadata()).thenReturn(spiedContent);

        Blob spiedBlob = Mockito.spy(realBlob);
        Mockito.when(spiedBlob.getMetadata()).thenReturn(spiedMeta);

        BlobStorage spy = createStorageSpy(realStorage);
        Mockito.when(spy.meta(blobKey)).thenReturn(spiedMeta);
        Mockito.when(spy.load(blobKey)).thenReturn(spiedBlob);
        return spy;
    }

    private static ResourceDescriptor fileResource(String name) {
        return new ResourceDescriptor(ResourceTypes.FILE, name, List.of(), "bucket", "bucket/", false);
    }

    private static Map<ResourceDescriptor, String> load(ResourceService target, List<ResourceDescriptor> descriptors) {
        List<Pair<ResourceItemMetadata, String>> loaded = new ArrayList<>();
        target.load(descriptors.stream().map(ResourceItemMetadata::new).toList(), loaded);
        return bodies(loaded);
    }

    private static Map<ResourceDescriptor, String> bodies(List<Pair<ResourceItemMetadata, String>> loaded) {
        Map<ResourceDescriptor, String> result = new HashMap<>();
        loaded.forEach(pair -> result.put(pair.getKey().getDescriptor(), pair.getValue()));
        return result;
    }

    private ResourceService serviceWithBlobStorage(BlobStorage blobStorage, String prefix) {
        return new ResourceService(Mockito.mock(TimerService.class), client, blobStorage,
                new LockService(client, prefix), settings, prefix);
    }

    private static ResourceDescriptor resource(String name) {
        return new ResourceDescriptor(ResourceTypes.CONVERSATION, name, List.of(), "bucket", "bucket/", false);
    }
}
