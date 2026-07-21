package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.json.JsonObject;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexResourceSweepServiceTest {

    private static final String MANIFEST = """
            ---
            name: Test Skill
            description: A skill used for tests
            ---
            # Body
            """;

    private RedisServer redisServer;
    private RedissonClient redis;
    private BlobStorage blobStorage;
    private Path testDir;
    private LockService lockService;
    private ResourceService resourceService;
    private ComplexResourceService complexResourceService;
    private EncryptionService encryptionService;
    private SimpleMeterRegistry meterRegistry;
    private final SkillHandler handler = new SkillHandler();

    @BeforeEach
    void init() throws IOException {
        try {
            // Metrics.globalRegistry is a bare CompositeMeterRegistry with no backing registry outside a
            // running AiDial instance, so counters registered on it never actually accumulate; attach a
            // real registry for the duration of the test so dial_complex_resource_sweep_* counters work.
            meterRegistry = new SimpleMeterRegistry();
            Metrics.addRegistry(meterRegistry);

            redisServer = RedisServer.newRedisServer()
                    .port(16381)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            redisServer.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16381");
            redis = Redisson.create(config);

            testDir = FileUtil.baseTestPath(ComplexResourceSweepServiceTest.class);
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
                    """.formatted(testDir.toString());
            ObjectMapper mapper = new ObjectMapper();
            Storage storageConfig = mapper.readValue(blobStorageConfig, Storage.class);
            blobStorage = new BlobStorage(storageConfig);

            TimerService timerService = Mockito.mock(TimerService.class);
            Mockito.when(timerService.scheduleWithFixedDelay(Mockito.anyLong(), Mockito.anyLong(), Mockito.any()))
                    .thenReturn(() -> { });
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
            resourceService = new ResourceService(timerService, redis, blobStorage, lockService, settings, null);

            complexResourceService = new ComplexResourceService(
                    resourceService, lockService, blobStorage, new ComplexResourceService.Settings());
            encryptionService = new EncryptionService(new JsonObject().put("secret", "secret").put("key", "key"));
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws IOException {
        try {
            if (meterRegistry != null) {
                Metrics.removeRegistry(meterRegistry);
                meterRegistry.close();
            }
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

    private ComplexResourceSweepService newSweepService(int batch, int activeBatches, long gracePeriod) {
        ComplexResourceSweepService.Settings settings = new ComplexResourceSweepService.Settings();
        settings.setPeriod(10_000);
        settings.setBatch(batch);
        settings.setActiveBatches(activeBatches);
        settings.setGracePeriod(gracePeriod);
        TimerService timerService = Mockito.mock(TimerService.class);
        Mockito.when(timerService.scheduleWithFixedDelay(Mockito.anyLong(), Mockito.anyLong(), Mockito.any()))
                .thenReturn(() -> { });
        return new ComplexResourceSweepService(
                timerService, blobStorage, redis, lockService, complexResourceService, encryptionService, settings);
    }

    private ResourceDescriptor skill(String name) {
        return new ResourceDescriptor(ResourceTypes.SKILL, name, List.of(), "public", "public/", false);
    }

    private void putSkill(ResourceDescriptor resource) {
        Map<String, Buffer> uploads = Map.of("SKILL.md", Buffer.buffer(MANIFEST));
        complexResourceService.put(resource, handler, uploads, EtagHeader.ANY, "user1");
    }

    private ResourceDescriptor versionFile(ResourceDescriptor resource, String versionId, String fileName) {
        return new ResourceDescriptor(resource.getType(), fileName,
                List.of(resource.getName(), "v", versionId), resource.getBucketName(), resource.getBucketLocation(), false);
    }

    private List<? extends StorageMetadata> listRefs() {
        PageSet<? extends StorageMetadata> page = blobStorage.list(
                ComplexResourceService.COMPLEX_RESOURCE_REFS_FOLDER, null, 1000, true);
        return page.stream().filter(meta -> meta.getType() == StorageType.BLOB).toList();
    }

    private void writeDanglingRef(String url) {
        String refId = java.util.UUID.randomUUID().toString().replace("-", "");
        String path = ComplexResourceService.COMPLEX_RESOURCE_REFS_FOLDER + "/" + refId + ".json";
        blobStorage.store(path, "application/json", null, Map.of(),
                ("{\"url\":\"" + url + "\"}").getBytes());
    }

    @Test
    void testDanglingRefIsDeletedAfterBucketLockConfirmsAbsence() {
        ResourceDescriptor resource = skill("neverCreated");
        writeDanglingRef(resource.getUrl());
        assertEquals(1, listRefs().size());

        double before = counterValue("dial_complex_resource_sweep_dangling_refs_total");
        ComplexResourceSweepService sweep = newSweepService(100, 1, 3_600_000L);
        sweep.tick();

        assertEquals(0, listRefs().size());
        assertEquals(before + 1, counterValue("dial_complex_resource_sweep_dangling_refs_total"));
    }

    @Test
    void testTickReclaimsDeletingResourcePastGracePeriod() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource);
        FolderResourceMarker marker = complexResourceService.readMarkerForSweep(resource);
        String versionId = marker.getCurrentVersion();
        complexResourceService.delete(resource, EtagHeader.ANY);

        double before = counterValue("dial_complex_resource_sweep_reclaimed_total");
        ComplexResourceSweepService sweep = newSweepService(100, 1, 0L);
        sweep.tick();

        assertNull(complexResourceService.readMarkerForSweep(resource));
        assertNull(resourceService.getResourceMetadata(versionFile(resource, versionId, "SKILL.md")));
        assertEquals(0, listRefs().size());
        assertEquals(before + 1, counterValue("dial_complex_resource_sweep_reclaimed_total"));
    }

    @Test
    void testTickDoesNotReclaimWithinGracePeriod() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource);
        complexResourceService.delete(resource, EtagHeader.ANY);

        ComplexResourceSweepService sweep = newSweepService(100, 1, 3_600_000L);
        sweep.tick();

        assertNotNull(complexResourceService.readMarkerForSweep(resource));
        assertEquals(1, listRefs().size());
    }

    @Test
    void testTickGcsObsoleteVersionPastGracePeriod() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource);
        FolderResourceMarker marker = complexResourceService.readMarkerForSweep(resource);
        String currentVersion = marker.getCurrentVersion();

        String orphanVersionId = "orphanversion";
        ResourceDescriptor orphanFile = versionFile(resource, orphanVersionId, "orphan.txt");
        resourceService.putFile(orphanFile, "x".getBytes(), EtagHeader.ANY, "text/plain", "user1");

        double before = counterValue("dial_complex_resource_sweep_orphan_versions_total");
        ComplexResourceSweepService sweep = newSweepService(100, 1, 0L);
        sweep.tick();

        assertNull(resourceService.getResourceMetadata(orphanFile));
        assertNotNull(resourceService.getResourceMetadata(versionFile(resource, currentVersion, "SKILL.md")));
        // Still active: the reference stays.
        assertEquals(1, listRefs().size());
        assertEquals(before + 1, counterValue("dial_complex_resource_sweep_orphan_versions_total"));
    }

    @Test
    void testTickSkipsWhenThisInstanceIsAtItsOwnActiveBatchesCap() throws Exception {
        ResourceDescriptor resource = skill("neverCreated");
        writeDanglingRef(resource.getUrl());

        ComplexResourceSweepService sweep = newSweepService(100, 1, 3_600_000L);
        Field field = ComplexResourceSweepService.class.getDeclaredField("activeBatchCount");
        field.setAccessible(true);
        AtomicInteger activeBatchCount = (AtomicInteger) field.get(sweep);

        // Simulate this instance already having one batch mid-processing (its own activeBatches=1 cap).
        activeBatchCount.set(1);
        try {
            sweep.tick();
            assertEquals(1, listRefs().size());
        } finally {
            activeBatchCount.set(0);
        }

        // Budget freed up: the same instance now processes the batch.
        sweep.tick();
        assertEquals(0, listRefs().size());
    }

    @Test
    void testActiveBatchesCapIsPerInstanceNotClusterWide() throws Exception {
        // Simulate two Core pods, each with its own activeBatches=1 instance. Saturating instance1's own
        // budget must not block instance2's independent budget, since the cap is enforced purely locally
        // (a plain instance field), not via a shared cluster-wide count.
        ResourceDescriptor resource = skill("neverCreated");
        writeDanglingRef(resource.getUrl());

        ComplexResourceSweepService instance1 = newSweepService(100, 1, 3_600_000L);
        ComplexResourceSweepService instance2 = newSweepService(100, 1, 3_600_000L);

        Field field = ComplexResourceSweepService.class.getDeclaredField("activeBatchCount");
        field.setAccessible(true);
        AtomicInteger instance1Count = (AtomicInteger) field.get(instance1);
        instance1Count.set(1);
        try {
            instance2.tick();
            // instance2's own budget was untouched, so it made progress despite instance1 being saturated.
            assertEquals(0, listRefs().size());
        } finally {
            instance1Count.set(0);
        }
    }

    @Test
    void testTickSkipsWhenAnotherTickHoldsTheCursorLock() throws InterruptedException {
        ResourceDescriptor resource = skill("neverCreated");
        writeDanglingRef(resource.getUrl());

        CountDownLatch holderAcquired = new CountDownLatch(1);
        CountDownLatch releaseSignal = new CountDownLatch(1);
        String lockKey = "complex_resource:cursor_state";
        Thread holder = new Thread(() -> {
            try (LockService.Lock ignored = lockService.lock(lockKey)) {
                holderAcquired.countDown();
                releaseSignal.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        holder.start();
        try {
            assertTrue(holderAcquired.await(5, TimeUnit.SECONDS));

            ComplexResourceSweepService sweep = newSweepService(100, 1, 3_600_000L);
            sweep.tick();

            // Another tick holds the cursor claim lock: this tick must skip, not block or steal work.
            assertEquals(1, listRefs().size());
        } finally {
            releaseSignal.countDown();
            holder.join(5000);
        }
    }

    private static double counterValue(String name) {
        Counter counter = Metrics.globalRegistry.find(name).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
