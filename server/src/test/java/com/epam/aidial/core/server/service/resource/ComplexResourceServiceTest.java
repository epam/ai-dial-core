package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.server.service.InvitationService;
import com.epam.aidial.core.server.service.ShareService;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;
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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComplexResourceServiceTest {

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
    private ResourceService resourceService;
    private ComplexResourceService complexResourceService;
    private final SkillHandler handler = new SkillHandler();

    @BeforeEach
    void init() throws IOException {
        try {
            redisServer = RedisServer.newRedisServer()
                    .port(16380)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            redisServer.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16380");
            redis = Redisson.create(config);

            testDir = FileUtil.baseTestPath(ComplexResourceServiceTest.class);
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
            LockService lockService = new LockService(redis, null);

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

            ShareService shareService = Mockito.mock(ShareService.class);
            InvitationService invitationService = Mockito.mock(InvitationService.class);
            complexResourceService = new ComplexResourceService(
                    resourceService, lockService, shareService, invitationService, blobStorage);
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

    private ResourceDescriptor skill(String name) {
        return new ResourceDescriptor(ResourceTypes.SKILL, name, List.of(), "public", "public/", false);
    }

    private void putSkill(ResourceDescriptor resource, EtagHeader etag) {
        Map<String, Buffer> uploads = Map.of("SKILL.md", Buffer.buffer(MANIFEST));
        complexResourceService.put(resource, handler, uploads, etag, "user1");
    }

    private List<StorageMetadata> listRefs() {
        PageSet<? extends StorageMetadata> page = blobStorage.list(
                ComplexResourceService.COMPLEX_RESOURCE_REFS_FOLDER, null, 1000, true);
        List<StorageMetadata> blobs = new ArrayList<>();
        for (StorageMetadata meta : page) {
            if (meta.getType() == StorageType.BLOB) {
                blobs.add(meta);
            }
        }
        return blobs;
    }

    private ResourceDescriptor versionFile(ResourceDescriptor resource, String versionId, String fileName) {
        return new ResourceDescriptor(resource.getType(), fileName,
                List.of(resource.getName(), "v", versionId), resource.getBucketName(), resource.getBucketLocation(), false);
    }

    @Test
    void testReferenceWrittenOnFirstCreationOnly() {
        ResourceDescriptor resource = skill("mySkill");

        putSkill(resource, EtagHeader.ANY);
        assertEquals(1, listRefs().size());

        // A second whole-resource write to the same path is a new version of an already-existing
        // resource, not a first creation, so it must not add another reference.
        putSkill(resource, EtagHeader.ANY);
        assertEquals(1, listRefs().size());
    }

    @Test
    void testDeleteOnlyTombstonesMarker() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource, EtagHeader.ANY);

        FolderResourceMarker before = complexResourceService.readMarkerForSweep(resource);
        assertNotNull(before);
        String versionId = before.getCurrentVersion();

        complexResourceService.delete(resource, EtagHeader.ANY);

        FolderResourceMarker after = complexResourceService.readMarkerForSweep(resource);
        assertNotNull(after);
        assertEquals("deleting", after.getState());
        assertNotNull(after.getDeletedAt());

        // The version tree and the marker itself are left in place for the sweep to reclaim.
        ResourceItemMetadata manifest = resourceService.getResourceMetadata(versionFile(resource, versionId, "SKILL.md"));
        assertNotNull(manifest);
        assertEquals(1, listRefs().size());
    }

    @Test
    void testReclaimDeletingResourceRespectsGracePeriod() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource, EtagHeader.ANY);
        FolderResourceMarker marker = complexResourceService.readMarkerForSweep(resource);
        String versionId = marker.getCurrentVersion();
        complexResourceService.delete(resource, EtagHeader.ANY);

        // Still within the grace period: nothing is reclaimed.
        assertFalse(complexResourceService.reclaimDeletingResource(resource, 3_600_000L));
        assertNotNull(complexResourceService.readMarkerForSweep(resource));
        assertNotNull(resourceService.getResourceMetadata(versionFile(resource, versionId, "SKILL.md")));

        // Past the grace period: the version tree and the marker are reclaimed.
        assertTrue(complexResourceService.reclaimDeletingResource(resource, 0L));
        assertNull(complexResourceService.readMarkerForSweep(resource));
        assertNull(resourceService.getResourceMetadata(versionFile(resource, versionId, "SKILL.md")));
    }

    @Test
    void testReclaimDeletingResourceNoOpWhenActive() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource, EtagHeader.ANY);

        assertFalse(complexResourceService.reclaimDeletingResource(resource, 0L));
        assertNotNull(complexResourceService.readMarkerForSweep(resource));
    }

    @Test
    void testGcObsoleteVersionsRespectsGracePeriod() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource, EtagHeader.ANY);
        FolderResourceMarker marker = complexResourceService.readMarkerForSweep(resource);
        String currentVersion = marker.getCurrentVersion();

        // Simulate an orphan version left behind by a previously failed inline delete in copyOnWrite.
        String orphanVersionId = "orphanversion";
        ResourceDescriptor orphanFile = versionFile(resource, orphanVersionId, "orphan.txt");
        resourceService.putFile(orphanFile, "x".getBytes(), EtagHeader.ANY, "text/plain", "user1");
        assertNotNull(resourceService.getResourceMetadata(orphanFile));

        // Still within the grace period: the orphan version is left alone.
        assertFalse(complexResourceService.gcObsoleteVersions(resource, 3_600_000L));
        assertNotNull(resourceService.getResourceMetadata(orphanFile));

        // Past the grace period: the orphan version is GC'd, the current version is untouched.
        assertTrue(complexResourceService.gcObsoleteVersions(resource, 0L));
        assertNull(resourceService.getResourceMetadata(orphanFile));
        assertNotNull(resourceService.getResourceMetadata(versionFile(resource, currentVersion, "SKILL.md")));
    }

    @Test
    void testGcObsoleteVersionsNoOpWhenDeleting() {
        ResourceDescriptor resource = skill("mySkill");
        putSkill(resource, EtagHeader.ANY);
        complexResourceService.delete(resource, EtagHeader.ANY);

        assertFalse(complexResourceService.gcObsoleteVersions(resource, 0L));
    }
}
