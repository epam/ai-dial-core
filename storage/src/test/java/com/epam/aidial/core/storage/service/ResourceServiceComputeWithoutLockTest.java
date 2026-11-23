package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
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
        sync = syncTask(timerService);
    }

    private static Runnable syncTask(TimerService timerService) {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(timerService, Mockito.times(2)).scheduleWithFixedDelay(anyLong(), anyLong(), tasks.capture());
        return tasks.getAllValues().get(0);
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
        ResourceService otherPod = newPod(Mockito.mock(TimerService.class));
        AtomicInteger calls = new AtomicInteger();

        service.computeResourceWithoutLock(descriptor, value -> {
            if (calls.incrementAndGet() == 1) {
                // another pod writes between this read and this write
                otherPod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
            }
            return increment(value);
        });

        assertEquals("2", service.getResource(descriptor));
        assertEquals(2, calls.get());
    }

    @Test
    void testRetriesOnTheRecord_WhenTheBlobReadReturnsTheStubOfNewRecord() {
        ResourceDescriptor descriptor = limit("requests");
        // this pod finds no record in Redis, and another pod creates the record and its stub before the blob is loaded
        BlobStorage loadingStorage = Mockito.spy(storage);
        AtomicBoolean created = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            if (created.compareAndSet(false, true)) {
                service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
            }
            return invocation.callRealMethod();
        }).when(loadingStorage).load(descriptor.getAbsoluteFilePath());
        ResourceService pod = new ResourceService(Mockito.mock(TimerService.class), client, loadingStorage,
                new LockService(client, null), settings, null);

        pod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertEquals("2", service.getResource(descriptor), "a value computed from the empty stub must not replace the record");
    }

    @Test
    void testGivesUp_WhenAnotherWriteLandsFirstOnEveryAttempt() {
        ResourceDescriptor descriptor = limit("requests");
        ResourceService otherPod = newPod(Mockito.mock(TimerService.class));
        AtomicInteger calls = new AtomicInteger();
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        Metrics.addRegistry(meterRegistry);

        try {
            assertThrows(IllegalStateException.class, () -> service.computeResourceWithoutLock(descriptor, value -> {
                calls.incrementAndGet();
                otherPod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
                return increment(value);
            }));

            // only the other pod's writes landed
            assertEquals(32, calls.get());
            assertEquals("32", service.getResource(descriptor));
            assertEquals(1, meterRegistry.get("dial_resource_compute_give_ups_total").counter().count());
        } finally {
            Metrics.removeRegistry(meterRegistry);
        }
    }

    @Test
    void testCreatesTheRecord_WhenReadLeftPlaceholderForMissingBlob() {
        ResourceDescriptor descriptor = limit("requests");
        // a limit check or a limit stats read comes before the first write
        assertNull(service.getResource(descriptor));
        assertTrue(record(RedisUtil.redisKey(descriptor, null)).isExists(), "the read caches a placeholder");

        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertEquals("1", service.getResource(descriptor));
    }

    @Test
    void testDoesNotPutTheStub_WhenSyncAlreadyUploadedTheRecord() throws IOException {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        // an entry left by an earlier write, so a sync is due as soon as the record is created
        queue().add(0, redisKey);
        ResourceService creatingPod = Mockito.spy(service);
        Mockito.doAnswer(invocation -> {
            Object written = invocation.callRealMethod();
            sync.run();
            return written;
        }).when(creatingPod).compareAndSet(eq(redisKey), any(), anyBoolean(), any());

        creatingPod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertEquals("1", blobBody(descriptor), "the stub must not replace the record the sync uploaded");
    }

    @Test
    void testQueuesTheRecord_WhenTheWriteOutcomeIsUnknown() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        // the write is applied and its reply is lost, as on a Redis timeout
        ResourceService failingPod = Mockito.spy(service);
        Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("timeout");
        }).when(failingPod).compareAndSet(eq(redisKey), any(), anyBoolean(), any());

        assertThrows(IllegalStateException.class,
                () -> failingPod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment));

        assertEquals("1", service.getResource(descriptor));
        assertTrue(queue().contains(redisKey), "a write that may have been applied must be queued for sync");
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
    void testComputesRecordOfAnotherTypeAndPublishesItsEvents() throws InterruptedException {
        ResourceDescriptor prompt = new ResourceDescriptor(ResourceTypes.PROMPT, "a", List.of(), "bucket", "bucket/", false);
        List<ResourceEvent.Action> actions = new CopyOnWriteArrayList<>();
        CountDownLatch received = new CountDownLatch(2);
        try (ResourceTopic.Subscription ignored = service.subscribeResources(List.of(prompt), event -> {
            actions.add(event.getAction());
            received.countDown();
        })) {
            service.computeResourceWithoutLock(prompt, ResourceServiceComputeWithoutLockTest::increment);
            service.computeResourceWithoutLock(prompt, ResourceServiceComputeWithoutLockTest::increment);

            assertEquals("2", service.getResource(prompt));
            assertTrue(received.await(5, TimeUnit.SECONDS), "a write of an event type publishes its event");
            assertEquals(List.of(ResourceEvent.Action.CREATE, ResourceEvent.Action.UPDATE), actions);
        }
    }

    @Test
    void testSyncDoesNotRecreate_WhenTheKeyWasDeletedDuringTheSync() {
        String redisKey = RedisUtil.redisKey(limit("requests"), null);

        // the sync uploaded a deleted record (no etag), and then the key was invalidated
        assertFalse(service.markSynced(redisKey, new byte[0]));
        assertFalse(record(redisKey).isExists());
    }

    @Test
    void testSyncDoesNotExpire_WhenTheRecordIsNoLongerSynced() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        sync.run();

        // the sync read the record as synced, and then a write landed
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertFalse(service.expireIfSynced(redisKey), "the sync puts the entry back when this returns false");
        assertEquals(-1, record(redisKey).remainTimeToLive(), "an unsynced record must not expire");
        assertTrue(service.expireIfSynced(RedisUtil.redisKey(limit("missing"), null)), "a missing record has nothing to sync");
    }

    @Test
    void testQueueKeepsTheFirstDeadline_WhileWritesContinue() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        // the deadline the first unsynced write set
        queue().add(100, redisKey);

        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertEquals(100.0, queue().getScore(redisKey), "a later write must not move the deadline");
    }

    @Test
    void testSyncRequeues_WhenWriteLandsDuringTheUpload() throws IOException {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        queue().add(100, redisKey);

        // another pod's sync uploads "1", and a write lands while it does
        BlobStorage uploadingStorage = Mockito.spy(storage);
        AtomicBoolean written = new AtomicBoolean();
        Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            if (written.compareAndSet(false, true)) {
                service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
            }
            return null;
        }).when(uploadingStorage).store(eq(descriptor.getAbsoluteFilePath()), any(), any(), any(), any());
        TimerService timerService = Mockito.mock(TimerService.class);
        new ResourceService(timerService, client, uploadingStorage, new LockService(client, null), settings, null);
        syncTask(timerService).run();

        assertEquals("1", blobBody(descriptor));
        assertEquals("false", new String(record(redisKey).get("synced"), StandardCharsets.UTF_8));
        assertTrue(queue().getScore(redisKey) > 100, "the write that landed during the upload must stay queued, with a new deadline");

        sync.run();
        assertEquals("2", blobBody(descriptor));
    }

    @Test
    void testSyncKeepsTheEntryQueued_WhenMarkingSyncedFails() throws IOException {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        // the upload succeeds and the mark fails, as on a Redis timeout
        ResourceService failingPod = Mockito.spy(service);
        Mockito.doThrow(new IllegalStateException("timeout")).when(failingPod).markSynced(eq(redisKey), any());
        failingPod.sync();
        assertTrue(queue().contains(redisKey), "a record that was not marked synced must stay queued");

        sync.run();
        assertEquals("1", blobBody(descriptor));
        assertEquals("true", new String(record(redisKey).get("synced"), StandardCharsets.UTF_8));
        assertFalse(queue().contains(redisKey));
    }

    @Test
    void testSyncRequeues_WhenWriteLandsBetweenTheMarkAndTheRemoval() throws IOException {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        // the write finds the entry of the first write still queued and adds nothing, then the sync drops that entry
        ResourceService syncingPod = Mockito.spy(service);
        Mockito.doAnswer(invocation -> {
            Object marked = invocation.callRealMethod();
            service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
            return marked;
        }).when(syncingPod).markSynced(eq(redisKey), any());
        syncingPod.sync();

        assertEquals("1", blobBody(descriptor));
        assertTrue(queue().contains(redisKey), "the write that landed after the mark must be queued");

        sync.run();
        assertEquals("2", blobBody(descriptor));
    }

    @Test
    void testSyncKeepsTheEntryQueued_WhenTheCheckAfterTheRemovalFails() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        ResourceService failingPod = Mockito.spy(service);
        Mockito.doThrow(new IllegalStateException("timeout")).when(failingPod).expireIfSynced(redisKey);
        failingPod.sync();

        assertTrue(queue().contains(redisKey), "a record that was not checked after the removal must stay queued");
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSyncKeepsTheEntryQueued_WhenTheRemovalFails() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        // the removal is applied and its reply is lost, as on a Redis timeout
        RedissonClient failingClient = Mockito.spy(client);
        Mockito.doAnswer(invocation -> {
            RScoredSortedSet<String> failingQueue = Mockito.spy((RScoredSortedSet<String>) invocation.callRealMethod());
            Mockito.doAnswer(removal -> {
                removal.callRealMethod();
                throw new IllegalStateException("timeout");
            }).when(failingQueue).remove(redisKey);
            return failingQueue;
        }).when(failingClient).getScoredSortedSet(QUEUE, StringCodec.INSTANCE);
        new ResourceService(Mockito.mock(TimerService.class), failingClient, storage, new LockService(client, null), settings, null).sync();

        assertTrue(queue().contains(redisKey), "a write that found the entry queued before the removal would be left unqueued");
    }

    @Test
    void testReadReturnsItsBlobCopyAndKeepsTheNewerRecord_WhenWriteLandsDuringTheCacheFill() {
        ResourceDescriptor descriptor = limit("requests");
        ResourceService reader = readerRacedByWrite(descriptor);

        // the reader returns its blob copy, and Redis keeps the newer write
        assertEquals("1", reader.getResource(descriptor));
        assertEquals("2", service.getResource(descriptor));
    }

    @Test
    void testStreamReturnsItsBlobCopyAndKeepsTheNewerRecord_WhenWriteLandsDuringTheCacheFill() throws IOException {
        ResourceDescriptor file = new ResourceDescriptor(ResourceTypes.FILE, "a", List.of(), "bucket", "bucket/", false);
        ResourceService reader = readerRacedByWrite(file);

        assertEquals("1", streamBody(reader, file));
        assertEquals("2", streamBody(service, file));
    }

    @Test
    void testUpdatesRecordWithNoCreationTime() {
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);
        // a blob written before created_at was kept in its metadata
        ResourceService.Result noCreatedAt = ResourceService.Result.builder()
                .body("1".getBytes(StandardCharsets.UTF_8)).etag("\"old\"").updatedAt(1L).contentType("application/json")
                .compress(true).contentLength(1L).resourceType("LIMIT").synced(true).build();
        service.putIfAbsent(redisKey, noCreatedAt);

        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertEquals("2", service.getResource(descriptor));
    }

    @Test
    void testUpdatesWhenTheCachedCopyExpiresAtOnce() throws IOException {
        settings.setResourceTypesExpiration(Map.of("LIMIT", 0L));
        TimerService timerService = Mockito.mock(TimerService.class);
        ResourceService pod = newPod(timerService);
        ResourceDescriptor descriptor = limit("requests");
        String redisKey = RedisUtil.redisKey(descriptor, null);

        pod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        syncTask(timerService).run();
        assertFalse(record(redisKey).isExists(), "a synced record with expiration 0 is gone at once");

        pod.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);

        assertEquals("2", pod.getResource(descriptor));
        assertEquals("1", blobBody(descriptor));
    }

    @Test
    void testCachesNoPlaceholder_WhenTheBlobIsMissingAndNothingIsWritten() {
        ResourceDescriptor descriptor = limit("requests");

        service.computeResourceWithoutLock(descriptor, value -> value);

        assertFalse(record(RedisUtil.redisKey(descriptor, null)).isExists());
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

    /**
     * Another pod that misses the cache of a record synced as "1": a write lands while it loads the blob.
     */
    private ResourceService readerRacedByWrite(ResourceDescriptor descriptor) {
        service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
        sync.run();
        client.getKeys().delete(RedisUtil.redisKey(descriptor, null));

        BlobStorage loadingStorage = Mockito.spy(storage);
        Mockito.doAnswer(invocation -> {
            Object blob = invocation.callRealMethod();
            service.computeResourceWithoutLock(descriptor, ResourceServiceComputeWithoutLockTest::increment);
            return blob;
        }).when(loadingStorage).load(descriptor.getAbsoluteFilePath());
        return new ResourceService(Mockito.mock(TimerService.class), client, loadingStorage,
                new LockService(client, null), settings, null);
    }

    private static String streamBody(ResourceService pod, ResourceDescriptor file) throws IOException {
        try (ResourceService.ResourceStream stream = pod.getResourceStream(file, EtagHeader.ANY)) {
            return new String(stream.inputStream().readAllBytes(), StandardCharsets.UTF_8);
        }
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
