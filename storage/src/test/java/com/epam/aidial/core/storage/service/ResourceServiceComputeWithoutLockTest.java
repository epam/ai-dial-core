package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.tuple.Pair;
import org.jclouds.blobstore.domain.Blob;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RMap;
import org.redisson.api.RScoredSortedSet;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.ByteArrayCodec;
import org.redisson.client.codec.StringCodec;
import org.redisson.codec.CompositeCodec;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;

class ResourceServiceComputeWithoutLockTest {

    private static final String QUEUE = "resource:queue";

    private RedisServer server;
    private RedissonClient client;
    private BlobStorage storage;
    private Path testDir;
    private ResourceService.Settings settings;
    private ResourceService service;
    private Runnable sync;

    @BeforeEach
    void init() throws IOException {
        server = RedisServer.newRedisServer()
                .port(16382)
                .bind("127.0.0.1")
                .setting("maxmemory 8M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        server.start();

        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:16382");
        client = Redisson.create(config);

        testDir = FileUtil.baseTestPath(ResourceServiceComputeWithoutLockTest.class);
        // a run killed before destroy() leaves its blobs behind
        FileUtil.deleteDir(testDir);
        FileUtil.createDir(testDir.resolve("test"));
        ObjectMapper mapper = new ObjectMapper();
        storage = new BlobStorage(mapper.readValue("""
                {
                    "bucket": "test",
                    "provider": "filesystem",
                    "identity": "access-key",
                    "credential": "secret-key",
                    "overrides": {
                      "jclouds.filesystem.basedir": %s
                    }
                }
                """.formatted(mapper.writeValueAsString(testDir.toString())), Storage.class));

        // syncDelay 0: a write is due for sync at once, so a test can run the sync itself
        settings = mapper.readValue("""
                {
                 "maxSize" : 67108864,
                 "maxSizeToCache": 1048576,
                 "syncPeriod": 60000,
                 "syncDelay": 0,
                 "syncBatch": 4096,
                 "cacheExpiration": 300000,
                 "compressionMinSize": 256
                }
                """, ResourceService.Settings.class);
        TimerService timerService = Mockito.mock(TimerService.class);
        service = newPod(timerService);

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(timerService, Mockito.times(2)).scheduleWithFixedDelay(anyLong(), anyLong(), tasks.capture());
        sync = tasks.getAllValues().get(0);
    }

    private ResourceService newPod(TimerService timerService) {
        return new ResourceService(timerService, client, storage, new LockService(client, null), settings, null);
    }

    @AfterEach
    void destroy() throws IOException {
        try {
            client.shutdown();
            storage.close();
        } finally {
            server.stop();
            FileUtil.deleteDir(testDir);
        }
    }

    @Test
    void testCreatesUpdatesAndListsTheRecord() {
        ResourceDescriptor descriptor = limit("requests");

        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertEquals("2", service.getResource(descriptor));
        // the stub in blob storage is what makes the record listable before its first sync
        List<Pair<ResourceItemMetadata, String>> listed = service.listResources(folder(), page -> {
        });
        assertEquals(List.of("2"), listed.stream().map(Pair::getValue).toList());
    }

    @Test
    void testBurstOfWritersOnTwoPodsLosesNoUpdate() throws Exception {
        ResourceDescriptor descriptor = limit("requests");
        // far more writers than compare-and-set attempts: they take turns on each pod, so only the two pods race
        List<ResourceService> pods = List.of(service, newPod(Mockito.mock(TimerService.class)));
        int writersPerPod = 256;
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (ResourceService pod : pods) {
                for (int i = 0; i < writersPerPod; i++) {
                    futures.add(executor.submit(() -> {
                        start.await();
                        pod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
                        return null;
                    }));
                }
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        }

        assertEquals(String.valueOf(pods.size() * writersPerPod), service.getResource(descriptor));
    }

    @Test
    void testRetriesOnTheNewerValueWhenAnotherWriteLandsFirst() {
        ResourceDescriptor descriptor = limit("requests");
        AtomicInteger calls = new AtomicInteger();

        service.computeResourceWithoutLock(descriptor, value -> {
            if (calls.incrementAndGet() == 1) {
                // another pod writes between this read and this write
                service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
            }
            return increment(value);
        });

        assertEquals("2", service.getResource(descriptor));
        assertEquals(2, calls.get());
    }

    @Test
    void testSyncUploadsTheRecord_AndKeepsTheNextWriteForTheNextSync() throws IOException {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);

        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        sync.run();
        assertEquals("1", blobBody(descriptor));
        assertTrue(record(redisKey).remainTimeToLive() > 0, "a synced record expires");
        assertFalse(queue().contains(redisKey));

        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        assertEquals(-1, record(redisKey).remainTimeToLive(), "an unsynced record must not expire");
        assertTrue(queue().contains(redisKey));

        sync.run();
        assertEquals("2", blobBody(descriptor));
    }

    @Test
    void testSyncDoesNotMarkSynced_WhenTheRecordChangedAfterUpload() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        byte[] uploadedEtag = record(redisKey).get("etag");

        // a write lands after the sync uploaded the first value
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertFalse(service.markSynced(redisKey, uploadedEtag));
        assertEquals("false", new String(record(redisKey).get("synced"), StandardCharsets.UTF_8));
        assertEquals(-1, record(redisKey).remainTimeToLive());
    }

    @Test
    void testDequeueKeepsAnEntry_WhenWriteMovedItIntoTheFuture() {
        RScoredSortedSet<String> queue = queue();
        queue.add(100, "due");
        queue.add(300, "moved");

        service.dequeueIfDue("due", 200);
        service.dequeueIfDue("moved", 200);

        assertFalse(queue.contains("due"));
        assertTrue(queue.contains("moved"));
    }

    @Test
    void testReadReturnsTheNewerRecord_WhenWriteLandsDuringTheCacheFill() {
        ResourceDescriptor descriptor = limit("requests");
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        sync.run();
        client.getKeys().delete(RedisUtil.redisKey(descriptor, null));

        // another pod's reader misses the cache, and a write lands while it loads "1" from blob storage
        BlobStorage loadingStorage = Mockito.spy(storage);
        Mockito.doAnswer(invocation -> {
            Object blob = invocation.callRealMethod();
            service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
            return blob;
        }).when(loadingStorage).load(descriptor.getAbsoluteFilePath());
        ResourceService reader = new ResourceService(Mockito.mock(TimerService.class), client, loadingStorage,
                new LockService(client, null), settings, null);

        assertEquals("2", reader.getResource(descriptor));
        assertEquals("2", service.getResource(descriptor));
    }

    @Test
    void testCacheFillDoesNotOverwrite_WhenRedisHasTheRecord() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        byte[] stale = "0".getBytes(StandardCharsets.UTF_8);
        ResourceService.Result blobCopy = ResourceService.Result.builder()
                .body(stale).etag("\"stale\"").createdAt(1L).updatedAt(1L).contentType("application/json")
                .compress(true).contentLength(1L).resourceType("LIMIT").synced(true).build();

        assertFalse(service.putIfAbsent(redisKey, blobCopy));
        assertEquals("1", service.getResource(descriptor));
    }

    private static String increment(String value) {
        return String.valueOf(value == null || value.isEmpty() ? 1 : Long.parseLong(value) + 1);
    }

    private RMap<String, byte[]> record(String redisKey) {
        return client.getMap(redisKey, new CompositeCodec(StringCodec.INSTANCE, ByteArrayCodec.INSTANCE));
    }

    private RScoredSortedSet<String> queue() {
        return client.getScoredSortedSet(QUEUE, StringCodec.INSTANCE);
    }

    private String blobBody(ResourceDescriptor descriptor) throws IOException {
        Blob blob = storage.load(descriptor.getAbsoluteFilePath());
        try (InputStream body = blob.getPayload().openStream()) {
            return new String(body.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static ResourceDescriptor limit(String name) {
        return new ResourceDescriptor(ResourceTypes.LIMIT, name, List.of(), "bucket", "bucket/", false);
    }

    private static ResourceDescriptor folder() {
        return new ResourceDescriptor(ResourceTypes.LIMIT, null, List.of(), "bucket", "bucket/", true);
    }
}
