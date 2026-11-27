package com.epam.aidial.core.server.service.resource;

import com.epam.aidial.core.server.FileUtil;
import com.epam.aidial.core.server.data.folder.FolderResourceMarker;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.MetadataBase;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.data.ResourceFolderMetadata;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.data.UserMetadata;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.service.TimerService;
import com.epam.aidial.core.storage.util.Base58;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.vertx.core.buffer.Buffer;
import lombok.SneakyThrows;
import org.jclouds.blobstore.domain.PageSet;
import org.jclouds.blobstore.domain.StorageMetadata;
import org.jclouds.blobstore.domain.StorageType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.invocation.InvocationOnMock;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    void init() throws IOException, InterruptedException {
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
                    """.formatted(testDir.toString().replace("\\", "\\\\"));
            ObjectMapper mapper = new ObjectMapper();
            Storage storageConfig = mapper.readValue(blobStorageConfig, Storage.class);
            blobStorage = Mockito.spy(new BlobStorage(storageConfig));
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> invokeRealMethod(invocation))).when(blobStorage)
                    .store(Mockito.anyString(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(byte[].class));
            Mockito.doAnswer(invocation -> FileUtil.retryFilesystemCall(() -> invokeRealMethod(invocation))).when(blobStorage)
                    .copy(Mockito.anyString(), Mockito.anyString(), Mockito.any());

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
            resourceService = Mockito.spy(new ResourceService(timerService, redis, blobStorage, lockService, settings, null));

            ComplexResourceService.Settings complexResourceSettings = new ComplexResourceService.Settings();
            complexResourceSettings.setMaxFiles(3);
            complexResourceSettings.setMaxTotalBytes(200);
            complexResourceSettings.setMaxFileSizeBytes(100);
            complexResourceService = new ComplexResourceService(
                    resourceService, lockService, blobStorage, complexResourceSettings);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterEach
    void destroy() throws IOException, InterruptedException {
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

    @SneakyThrows
    private static Object invokeRealMethod(InvocationOnMock invocation) {
        return invocation.callRealMethod();
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

    @Test
    void testPutRejectsExceedingMaxFiles() {
        ResourceDescriptor resource = skill("tooManyFiles");
        Map<String, Buffer> uploads = Map.of(
                "SKILL.md", Buffer.buffer(MANIFEST),
                "a.txt", Buffer.buffer("a"),
                "b.txt", Buffer.buffer("b"),
                "c.txt", Buffer.buffer("c"));

        assertThrowsBadRequest(() -> complexResourceService.put(resource, handler, uploads, EtagHeader.ANY, "user1"));
        assertNull(complexResourceService.readMarkerForSweep(resource));
    }

    @Test
    void testPutAcceptsExactlyMaxFiles() {
        ResourceDescriptor resource = skill("exactMaxFiles");
        Map<String, Buffer> uploads = Map.of(
                "SKILL.md", Buffer.buffer(MANIFEST),
                "a.txt", Buffer.buffer("a"),
                "b.txt", Buffer.buffer("b"));

        complexResourceService.put(resource, handler, uploads, EtagHeader.ANY, "user1");
        assertNotNull(complexResourceService.readMarkerForSweep(resource));
    }

    @Test
    void testPutRejectsExceedingMaxTotalBytes() {
        ResourceDescriptor resource = skill("tooBigTotal");
        Map<String, Buffer> uploads = Map.of(
                "SKILL.md", Buffer.buffer(MANIFEST),
                "big.txt", Buffer.buffer("x".repeat(200)));

        HttpException e = assertThrows(HttpException.class,
                () -> complexResourceService.put(resource, handler, uploads, EtagHeader.ANY, "user1"));
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, e.getStatus());
        assertNull(complexResourceService.readMarkerForSweep(resource));
    }

    @Test
    void testPutRejectsExceedingMaxFileSize() {
        ResourceDescriptor resource = skill("tooBigFile");
        Map<String, Buffer> uploads = Map.of(
                "SKILL.md", Buffer.buffer(MANIFEST),
                "huge.txt", Buffer.buffer("x".repeat(150)));

        HttpException e = assertThrows(HttpException.class,
                () -> complexResourceService.put(resource, handler, uploads, EtagHeader.ANY, "user1"));
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, e.getStatus());
        assertNull(complexResourceService.readMarkerForSweep(resource));
    }

    @Test
    void testPutFileRejectsExceedingMaxFileSize() {
        ResourceDescriptor resource = skill("fileTooBig");
        putSkill(resource, EtagHeader.ANY);

        HttpException e = assertThrows(HttpException.class, () -> complexResourceService.putFile(
                resource, handler, "big.txt", "x".repeat(150).getBytes(), EtagHeader.ANY, "user1"));
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, e.getStatus());
    }

    @Test
    void testPutFileRejectsExceedingMaxTotalBytes() {
        ResourceDescriptor resource = skill("fileTotalTooBig");
        putSkill(resource, EtagHeader.ANY);
        FolderResourceMarker before = complexResourceService.readMarkerForSweep(resource);

        // SKILL.md (68 bytes) + a 190-byte file tips the aggregate past maxTotalBytes(200).
        HttpException e = assertThrows(HttpException.class, () -> complexResourceService.putFile(
                resource, handler, "big.txt", "x".repeat(190).getBytes(), EtagHeader.ANY, "user1"));
        assertEquals(HttpStatus.REQUEST_ENTITY_TOO_LARGE, e.getStatus());

        // rejected mutation must not have changed the resource
        FolderResourceMarker after = complexResourceService.readMarkerForSweep(resource);
        assertEquals(before.getEtag(), after.getEtag());
    }

    @Test
    void testCopyResourceResynthesizesFreshMarker() throws InterruptedException {
        ResourceDescriptor source = skill("source-skill");
        Map<String, Buffer> uploads = Map.of(
                "SKILL.md", Buffer.buffer(MANIFEST),
                "scripts/run.sh", Buffer.buffer("echo hi"));
        complexResourceService.put(source, handler, uploads, EtagHeader.ANY, "author1");
        FolderResourceMarker sourceMarker = complexResourceService.readMarkerForSweep(source);

        // updatedAt has millisecond resolution; force a visible difference at the destination.
        Thread.sleep(5);

        ResourceDescriptor destination = skill("dest-skill");
        boolean copied = complexResourceService.copyResource(source, destination, "author2", false);
        assertTrue(copied);

        FolderResourceMarker destMarker = complexResourceService.readMarkerForSweep(destination);
        assertNotNull(destMarker);

        // the marker is re-synthesized, not carried over verbatim: fresh version, fresh timestamps/author...
        assertNotEquals(sourceMarker.getCurrentVersion(), destMarker.getCurrentVersion());
        assertNotEquals(sourceMarker.getCreatedAt(), destMarker.getCreatedAt());
        assertNotEquals(sourceMarker.getUpdatedAt(), destMarker.getUpdatedAt());
        assertEquals("author2", destMarker.getAuthor());
        // ...but the aggregate etag is recomputed (not copied) from the copied files, which happen to be
        // byte-identical to the source, and the type-specific metadata (parsed from SKILL.md) is unchanged.
        assertEquals(sourceMarker.getEtag(), destMarker.getEtag());
        assertEquals(sourceMarker.getMetadata(), destMarker.getMetadata());

        // the destination's version tree is a real, independent copy of the files
        ResourceItemMetadata manifest = resourceService.getResourceMetadata(
                versionFile(destination, destMarker.getCurrentVersion(), "SKILL.md"));
        assertNotNull(manifest);
        ResourceItemMetadata script = resourceService.getResourceMetadata(
                versionFile(destination, destMarker.getCurrentVersion(), "scripts/run.sh"));
        assertNotNull(script);
    }

    @Test
    void testCopyResourceSkipsWhenDestinationActiveAndNotOverwriting() {
        ResourceDescriptor source = skill("source-skip");
        putSkill(source, EtagHeader.ANY);
        ResourceDescriptor destination = skill("dest-skip");
        putSkill(destination, EtagHeader.ANY);
        FolderResourceMarker destBefore = complexResourceService.readMarkerForSweep(destination);

        assertFalse(complexResourceService.copyResource(source, destination, "author2", false));

        FolderResourceMarker destAfter = complexResourceService.readMarkerForSweep(destination);
        assertEquals(destBefore.getCurrentVersion(), destAfter.getCurrentVersion());
    }

    @Test
    void testCopyResourceThrowsWhenSourceAbsent() {
        ResourceDescriptor source = skill("never-existed-source");
        ResourceDescriptor destination = skill("dest-for-absent-source");

        HttpException e = assertThrows(HttpException.class,
                () -> complexResourceService.copyResource(source, destination, "author2", false));
        assertEquals(HttpStatus.NOT_FOUND, e.getStatus());
        assertNull(complexResourceService.readMarkerForSweep(destination));
    }

    @Test
    void testPutFileRejectsExceedingMaxFiles() {
        ResourceDescriptor resource = skill("fileCountTooBig");
        Map<String, Buffer> uploads = Map.of(
                "SKILL.md", Buffer.buffer(MANIFEST),
                "a.txt", Buffer.buffer("a"),
                "b.txt", Buffer.buffer("b"));
        complexResourceService.put(resource, handler, uploads, EtagHeader.ANY, "user1");

        // adding a 4th distinct file exceeds maxFiles(3)
        HttpException e = assertThrows(HttpException.class, () -> complexResourceService.putFile(
                resource, handler, "c.txt", "c".getBytes(), EtagHeader.ANY, "user1"));
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());

        // replacing an existing file is fine (file count unchanged)
        complexResourceService.putFile(resource, handler, "a.txt", "aa".getBytes(), EtagHeader.ANY, "user1");
    }

    private FileMetadata putFile(ResourceDescriptor resource, String path, String content) {
        return complexResourceService.putFile(resource, handler, path, content.getBytes(StandardCharsets.UTF_8),
                EtagHeader.ANY, "application/x-custom", "writer").metadata();
    }

    private FileMetadata fileMetadata(ResourceDescriptor resource, String path) {
        return (FileMetadata) complexResourceService.getFileMetadata(resource, path, null, 100, false);
    }

    private static void assertStatus(HttpStatus status, Runnable action) {
        assertEquals(status, assertThrows(HttpException.class, action::run).getStatus());
    }

    private record Condition(String ifMatch, String ifNoneMatch, HttpStatus failure) {
        EtagHeader header(String method) {
            return EtagHeader.fromHeader(ifMatch, ifNoneMatch, method);
        }
    }

    private static List<Condition> conditions(String etag, HttpStatus noneMatchFailure) {
        return List.of(
                new Condition(null, null, null),
                new Condition(etag, null, null),
                new Condition("\"other\"," + etag, null, null),
                new Condition("*", null, null),
                new Condition("\"other\"", null, HttpStatus.PRECONDITION_FAILED),
                new Condition(null, "\"other\"", null),
                new Condition(null, etag, noneMatchFailure),
                new Condition(null, "\"other\"," + etag, noneMatchFailure),
                new Condition(null, "*", noneMatchFailure),
                new Condition(etag, "\"other\"", null),
                new Condition("\"other\"", etag, HttpStatus.PRECONDITION_FAILED));
    }

    @Test
    void testFileConditionalGetMatrix() throws IOException {
        ResourceDescriptor resource = skill("conditional-get");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata file = putFile(resource, "a.txt", "original");
        String aggregate = complexResourceService.getMarker(resource).getEtag();
        for (Condition condition : conditions(file.getEtag(), HttpStatus.NOT_MODIFIED)) {
            if (condition.failure() != null) {
                HttpException failure = assertThrows(HttpException.class,
                        () -> complexResourceService.getFileStream(resource, "a.txt", condition.header("GET")));
                assertEquals(condition.failure(), failure.getStatus());
                if (failure.getStatus() == HttpStatus.NOT_MODIFIED) {
                    assertEquals(file.getEtag(), failure.getHeaders().get("etag"));
                }
            } else {
                ComplexResourceService.FileReadResult read = complexResourceService.getFileStream(resource, "a.txt", condition.header("GET"));
                try (ResourceService.ResourceStream stream = read.stream()) {
                    assertEquals(file.getEtag(), stream.etag());
                    assertEquals(aggregate, read.resourceEtag());
                    assertEquals("original", new String(stream.inputStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            }
        }
        assertNull(complexResourceService.getFileStream(resource, "absent.txt", EtagHeader.ANY));
        assertNull(complexResourceService.getFileStream(resource, "absent.txt", EtagHeader.fromHeader(null, "*", "GET")));
        assertNull(complexResourceService.getFileStream(resource, "absent.txt", EtagHeader.fromHeader("*", null, "GET")));
        assertNull(complexResourceService.getFileStream(resource, "absent.txt", EtagHeader.fromHeader(file.getEtag(), null, "GET")));
    }

    @Test
    void testMissingFileConditionalGetMatchesPlainStream() throws IOException {
        ResourceDescriptor resource = skill("missing-get-parity");
        putSkill(resource, EtagHeader.ANY);
        ResourceDescriptor plain = new ResourceDescriptor(ResourceTypes.FILE, "absent.txt", List.of(), "public", "public/", false);
        for (EtagHeader condition : List.of(EtagHeader.ANY,
                EtagHeader.fromHeader("*", null, "GET"),
                EtagHeader.fromHeader("\"unknown\"", null, "GET"),
                EtagHeader.fromHeader(null, "*", "GET"),
                EtagHeader.fromHeader(null, "\"unknown\"", "GET"))) {
            assertNull(resourceService.getResourceStream(plain, condition));
            assertNull(complexResourceService.getFileStream(resource, "absent.txt", condition));
        }
    }

    @Test
    void testFileConditionalPutMatrix() {
        ResourceDescriptor resource = skill("conditional-put");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata file = putFile(resource, "a.txt", "original");
        for (Condition original : conditions(file.getEtag(), HttpStatus.PRECONDITION_FAILED)) {
            file = putFile(resource, "a.txt", "original");
            // Equal bytes retain their ETag even though each reset changes the resource version.
            String before = complexResourceService.getMarker(resource).getCurrentVersion();
            Runnable write = () -> complexResourceService.putFile(resource, handler, "a.txt", "changed".getBytes(StandardCharsets.UTF_8),
                    original.header("PUT"), "text/custom", "user2");
            if (original.failure() != null) {
                assertStatus(original.failure(), write);
                assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
            } else {
                write.run();
                assertNotEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
            }
        }
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.putFile(resource, handler, "new.txt", new byte[0],
                EtagHeader.fromHeader("*", null, "PUT"), "text/plain", "user2"));
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.putFile(resource, handler, "new.txt", new byte[0],
                EtagHeader.fromHeader("\"unknown\"", null, "PUT"), "text/plain", "user2"));
        ComplexResourceService.FileMutationResult created = complexResourceService.putFile(resource, handler, "new.txt", new byte[0],
                EtagHeader.fromHeader(null, "*", "PUT"), "text/plain", "user2");
        assertNotNull(created.metadata());
    }

    @Test
    void testFileConditionalDeleteMatrix() {
        ResourceDescriptor resource = skill("conditional-delete");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata original = putFile(resource, "a.txt", "original");
        for (Condition condition : conditions(original.getEtag(), HttpStatus.PRECONDITION_FAILED)) {
            putFile(resource, "a.txt", "original");
            String before = complexResourceService.getMarker(resource).getCurrentVersion();
            Runnable delete = () -> complexResourceService.deleteFileWithFileEtag(resource, handler, "a.txt", condition.header("DELETE"), "user2");
            if (condition.failure() != null) {
                assertStatus(condition.failure(), delete);
                assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
                assertNotNull(fileMetadata(resource, "a.txt"));
            } else {
                delete.run();
                assertNull(fileMetadata(resource, "a.txt"));
            }
        }
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.deleteFileWithFileEtag(resource, handler, "absent.txt",
                EtagHeader.fromHeader("*", null, "DELETE"), "user2"));
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.deleteFileWithFileEtag(resource, handler, "absent.txt",
                EtagHeader.fromHeader("\"unknown\"", null, "DELETE"), "user2"));
        assertStatus(HttpStatus.NOT_FOUND, () -> complexResourceService.deleteFileWithFileEtag(resource, handler, "absent.txt", EtagHeader.ANY, "user2"));
        assertStatus(HttpStatus.NOT_FOUND, () -> complexResourceService.deleteFileWithFileEtag(resource, handler, "absent.txt",
                EtagHeader.fromHeader(null, "*", "DELETE"), "user2"));
    }

    @Test
    void testExplicitMimeAndFullResponseMetadata() throws IOException {
        ResourceDescriptor resource = new ResourceDescriptor(ResourceTypes.SKILL, "mime-skill", List.of("team"), "public", "public/", false);
        putSkill(resource, EtagHeader.ANY);
        ComplexResourceService.FileMutationResult written = complexResourceService.putFile(resource, handler, "refs/a.md", new byte[]{1, 2, 3},
                EtagHeader.ANY, "image/png", "mime-author");
        FileMetadata metadata = written.metadata();
        assertEquals("image/png", metadata.getContentType());
        assertEquals(3, metadata.getContentLength());
        assertEquals("mime-author", metadata.getAuthor());
        assertNotNull(metadata.getCreatedAt());
        assertNotNull(metadata.getUpdatedAt());
        assertEquals("skills/public/team/mime-skill/files/refs/a.md", metadata.getUrl());
        assertEquals("team/mime-skill/files/refs", metadata.getParentPath());
        assertEquals(ResourceTypes.SKILL, metadata.getResourceType());
        assertEquals(new FileMetadata.ResourceReference(resource.getUrl(), written.resourceEtag()), metadata.getResource());
        assertEquals(metadata.getEtag(), fileMetadata(resource, "refs/a.md").getEtag());
        ComplexResourceService.FileReadResult read = complexResourceService.getFileStream(resource, "refs/a.md", EtagHeader.ANY);
        try (ResourceService.ResourceStream stream = read.stream()) {
            assertEquals("image/png", stream.contentType());
            assertEquals(metadata.getEtag(), stream.etag());
            assertEquals(written.resourceEtag(), read.resourceEtag());
        }
    }

    @Test
    void testLegacyMarkerFileMapFallbackAndAggregateOverloads() throws IOException {
        ResourceDescriptor resource = skill("legacy-marker");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata file = putFile(resource, "a.txt", "a");
        FolderResourceMarker marker = complexResourceService.getMarker(resource);
        marker.setFileMetadata(null);
        ResourceDescriptor markerDescriptor = ComplexResourceService.getMarkerLockDescriptor(resource);
        resourceService.putResource(markerDescriptor, new ObjectMapper().writeValueAsString(marker), EtagHeader.ANY, "user1");
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.putFile(resource, handler, "a.txt", new byte[]{2},
                EtagHeader.fromHeader(marker.getEtag(), null, "PUT"), "text/plain", "user2"));
        ComplexResourceService.FileMutationResult changed = complexResourceService.putFile(resource, handler, "a.txt", new byte[]{2},
                EtagHeader.fromHeader(file.getEtag(), null, "PUT"), "text/plain", "user2");
        assertNotNull(complexResourceService.getMarker(resource).getFileMetadata());
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.putFile(resource, handler, "a.txt", new byte[]{3},
                EtagHeader.fromHeader(changed.metadata().getEtag(), null, "PUT"), "user2"));
        String aggregate = complexResourceService.putFile(resource, handler, "a.txt", new byte[]{3},
                EtagHeader.fromHeader(changed.resourceEtag(), null, "PUT"), "user2");
        assertEquals(aggregate, complexResourceService.getMarker(resource).getEtag());
        String deleted = complexResourceService.deleteFile(resource, handler, "a.txt", EtagHeader.fromHeader(aggregate, null, "DELETE"), "user2");
        assertEquals(deleted, complexResourceService.getMarker(resource).getEtag());
    }

    @Test
    void testIndependentFileWritersAndCapturedResults() throws Exception {
        ResourceDescriptor resource = skill("concurrent-files");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata a = putFile(resource, "a.txt", "a");
        FileMetadata b = putFile(resource, "b.txt", "b");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<ComplexResourceService.FileMutationResult> first = CompletableFuture.supplyAsync(() -> {
            await(start);
            return complexResourceService.putFile(resource, handler, "a.txt", "aa".getBytes(StandardCharsets.UTF_8),
                    EtagHeader.fromHeader(a.getEtag(), null, "PUT"), "text/a", "author-a");
        });
        CompletableFuture<ComplexResourceService.FileMutationResult> second = CompletableFuture.supplyAsync(() -> {
            await(start);
            return complexResourceService.putFile(resource, handler, "b.txt", "bb".getBytes(StandardCharsets.UTF_8),
                    EtagHeader.fromHeader(b.getEtag(), null, "PUT"), "text/b", "author-b");
        });
        start.countDown();
        ComplexResourceService.FileMutationResult resultA = first.get(10, TimeUnit.SECONDS);
        ComplexResourceService.FileMutationResult resultB = second.get(10, TimeUnit.SECONDS);
        assertEquals(resultA.resourceEtag(), resultA.metadata().getResource().etag());
        assertEquals(resultB.resourceEtag(), resultB.metadata().getResource().etag());
        assertNotEquals(resultA.resourceEtag(), resultB.resourceEtag());
        assertEquals(resultA.metadata().getEtag(), fileMetadata(resource, "a.txt").getEtag());
        assertEquals(resultB.metadata().getEtag(), fileMetadata(resource, "b.txt").getEtag());
        assertEquals(a.getAuthor(), resultA.metadata().getAuthor());
        assertEquals(b.getAuthor(), resultB.metadata().getAuthor());
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.putFile(resource, handler, "a.txt", new byte[]{3},
                EtagHeader.fromHeader(a.getEtag(), null, "PUT"), "text/plain", "user2"));
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(10, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    @Test
    void testStreamSnapshotSurvivesLaterMutation() throws IOException {
        ResourceDescriptor resource = skill("stream-snapshot");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata before = putFile(resource, "a.txt", "old");
        ComplexResourceService.FileReadResult read = complexResourceService.getFileStream(resource, "a.txt", EtagHeader.ANY);
        try (ResourceService.ResourceStream stream = read.stream()) {
            ComplexResourceService.FileMutationResult after = complexResourceService.putFile(resource, handler, "a.txt", new byte[]{4},
                    EtagHeader.ANY, "text/new", "user2");
            assertEquals(before.getEtag(), stream.etag());
            assertEquals(before.getResource().etag(), read.resourceEtag());
            assertNotEquals(after.resourceEtag(), read.resourceEtag());
            assertEquals("old", new String(stream.inputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test
    void testMetadataFolderProjectionPaginationAndRecursion() {
        ResourceDescriptor resource = skill("metadata-folder");
        complexResourceService.put(resource, handler, Map.of("SKILL.md", Buffer.buffer(MANIFEST),
                "refs/a.md", Buffer.buffer("a"), "refs/nested/b.txt", Buffer.buffer("b")), EtagHeader.ANY, "user1");
        ResourceFolderMetadata root = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "", null, 100, false);
        assertEquals(resource.getUrl() + "/files/", root.getUrl());
        assertEquals(2, root.getItems().size());
        ResourceFolderMetadata nested = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", null, 100, false);
        assertEquals(resource.getUrl() + "/files/refs/", nested.getUrl());
        assertEquals(2, nested.getItems().size());
        ResourceFolderMetadata zeroPage = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", null, 0, false);
        assertNotNull(zeroPage);
        assertEquals(resource.getUrl() + "/files/refs/", zeroPage.getUrl());
        assertTrue(zeroPage.getItems().isEmpty());
        assertNull(complexResourceService.getFileMetadata(resource, "missing/", null, 0, false));
        Set<String> urls = new HashSet<>();
        String token = null;
        do {
            ResourceFolderMetadata page = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", token, 1, true);
            assertNotNull(page);
            for (MetadataBase item : page.getItems()) {
                FileMetadata file = (FileMetadata) item;
                assertTrue(file.getUrl().startsWith(resource.getUrl() + "/files/refs/"));
                assertNotNull(file.getEtag());
                assertNotNull(file.getContentType());
                assertNotNull(file.getCreatedAt());
                assertEquals("user1", file.getAuthor());
                assertEquals(new FileMetadata.ResourceReference(resource.getUrl(), complexResourceService.getMarker(resource).getEtag()), file.getResource());
                assertTrue(urls.add(file.getUrl()));
            }
            token = page.getNextToken();
        } while (token != null);
        assertEquals(Set.of(resource.getUrl() + "/files/refs/a.md", resource.getUrl() + "/files/refs/nested/b.txt"), urls);
        assertNull(complexResourceService.getFileMetadata(resource, "missing.txt", null, 100, false));
        assertNull(complexResourceService.getFileMetadata(resource, "missing/", null, 100, false));
    }

    @Test
    void testAbsentAndDeletingRootsAndEmptyProject() {
        ResourceDescriptor resource = skill("absent-root");
        assertNull(complexResourceService.getFileMetadata(resource, "a.txt", null, 100, false));
        assertNull(complexResourceService.getFileStream(resource, "a.txt", EtagHeader.ANY));
        HttpException missing = assertThrows(HttpException.class, () -> putFile(resource, "a.txt", "a"));
        assertEquals(HttpStatus.NOT_FOUND, missing.getStatus());
        assertTrue(missing.getMessage().contains("PUT /v2/" + resource.getUrl()));
        putSkill(resource, EtagHeader.ANY);
        complexResourceService.delete(resource, EtagHeader.ANY);
        assertNull(complexResourceService.getFileMetadata(resource, "", null, 100, false));
        assertStatus(HttpStatus.NOT_FOUND, () -> putFile(resource, "a.txt", "a"));
        ResourceDescriptor project = new ResourceDescriptor(ResourceTypes.PROJECT, "empty-project", List.of(), "public", "public/", false);
        ProjectHandler projectHandler = new ProjectHandler();
        complexResourceService.put(project, projectHandler, Map.of("a.txt", Buffer.buffer("a")), EtagHeader.ANY, "user1");
        String deleted = complexResourceService.deleteFileWithFileEtag(project, projectHandler, "a.txt", EtagHeader.ANY, "user2");
        assertEquals(deleted, complexResourceService.getMarker(project).getEtag());
        ResourceFolderMetadata empty = (ResourceFolderMetadata) complexResourceService.getFileMetadata(project, "", null, 100, true);
        assertNotNull(empty);
        assertTrue(empty.getItems().isEmpty());
    }

    @Test
    void testMutationRollbackBeforeMarkerCommit() {
        ResourceDescriptor resource = skill("failed-commit");
        putSkill(resource, EtagHeader.ANY);
        FolderResourceMarker before = complexResourceService.getMarker(resource);
        Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("file write failure");
        }).when(resourceService).putFile(Mockito.any(), Mockito.any(byte[].class), Mockito.any(),
                Mockito.eq("application/x-custom"), Mockito.eq("writer"));
        assertThrows(IllegalStateException.class, () -> putFile(resource, "a.txt", "a"));
        assertEquals(before.getCurrentVersion(), complexResourceService.getMarker(resource).getCurrentVersion());
        assertNull(fileMetadata(resource, "a.txt"));
        assertNotNull(fileMetadata(resource, "SKILL.md"));
        ArgumentCaptor<ResourceDescriptor> uploaded = ArgumentCaptor.forClass(ResourceDescriptor.class);
        Mockito.verify(resourceService).putFile(uploaded.capture(), Mockito.any(byte[].class), Mockito.any(),
                Mockito.eq("application/x-custom"), Mockito.eq("writer"));
        assertNull(resourceService.getResourceMetadata(uploaded.getValue()));
    }

    @Test
    void testCleanupFailureAfterCommitDoesNotRollback() {
        ResourceDescriptor resource = skill("failed-cleanup");
        putSkill(resource, EtagHeader.ANY);
        FolderResourceMarker before = complexResourceService.getMarker(resource);
        Mockito.doThrow(new IllegalStateException("cleanup failure")).when(resourceService)
                .deleteResource(Mockito.eq(versionFile(resource, before.getCurrentVersion(), "SKILL.md")), Mockito.any());
        ComplexResourceService.FileMutationResult written = complexResourceService.putFile(resource, handler, "a.txt", new byte[]{7},
                EtagHeader.ANY, "text/custom", "user2");
        assertEquals(written.resourceEtag(), complexResourceService.getMarker(resource).getEtag());
        assertNotEquals(before.getCurrentVersion(), complexResourceService.getMarker(resource).getCurrentVersion());
        assertNotNull(fileMetadata(resource, "SKILL.md"));
        assertEquals(written.metadata().getEtag(), fileMetadata(resource, "a.txt").getEtag());
        Mockito.doCallRealMethod().when(resourceService).deleteResource(
                Mockito.eq(versionFile(resource, before.getCurrentVersion(), "SKILL.md")), Mockito.any());
        assertTrue(complexResourceService.gcObsoleteVersions(resource, 0));
        assertNotNull(fileMetadata(resource, "a.txt"));
    }

    @Test
    void testSameResourceRenameAtLimitsIsOneCommit() {
        ResourceDescriptor resource = skill("rename-limit");
        complexResourceService.put(resource, handler, Map.of("SKILL.md", Buffer.buffer(MANIFEST),
                "a.txt", Buffer.buffer("a"), "b.txt", Buffer.buffer("b")), EtagHeader.ANY, "user1");
        FileMetadata original = fileMetadata(resource, "a.txt");
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        Mockito.clearInvocations(resourceService);
        String etag = complexResourceService.copyFile(resource, handler, "a.txt", "renamed.txt", true, false, "user2");
        Mockito.verify(resourceService).lockResource(ComplexResourceService.getMarkerLockDescriptor(resource));
        assertNotEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        assertEquals(etag, complexResourceService.getMarker(resource).getEtag());
        assertNull(fileMetadata(resource, "a.txt"));
        FileMetadata renamed = fileMetadata(resource, "renamed.txt");
        assertEquals(original.getEtag(), renamed.getEtag());
        assertEquals(original.getContentType(), renamed.getContentType());
        Mockito.verify(resourceService, Mockito.times(1)).putResource(Mockito.eq(ComplexResourceService.getMarkerLockDescriptor(resource)),
                Mockito.anyString(), Mockito.any(), Mockito.eq("user2"), Mockito.eq(false));
        Mockito.verify(resourceService, Mockito.times(1)).publishLogicalEvent(Mockito.eq(resource), Mockito.eq(ResourceEvent.Action.UPDATE),
                Mockito.anyLong(), Mockito.eq(etag));
        Mockito.verify(resourceService).publishLogicalEvent(Mockito.argThat(file -> file.getUrl().equals(resource.getUrl() + "/files/a.txt")),
                Mockito.eq(ResourceEvent.Action.DELETE), Mockito.anyLong(), Mockito.isNull());
        Mockito.verify(resourceService).publishLogicalEvent(Mockito.argThat(file -> file.getUrl().equals(resource.getUrl() + "/files/renamed.txt")),
                Mockito.eq(ResourceEvent.Action.CREATE), Mockito.anyLong(), Mockito.eq(original.getEtag()));
        Mockito.verify(resourceService, Mockito.never()).getResourceBytesLocked(
                Mockito.argThat(file -> !ComplexResourceService.MARKER_NAME.equals(file.getName())), Mockito.any());
    }

    @Test
    void testSameResourceCopyOverwriteLimitsAndDeleteValidation() {
        ResourceDescriptor resource = skill("copy-limits");
        complexResourceService.put(resource, handler, Map.of("SKILL.md", Buffer.buffer(MANIFEST),
                "a.txt", Buffer.buffer("a"), "b.txt", Buffer.buffer("b")), EtagHeader.ANY, "user1");
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        assertStatus(HttpStatus.CONFLICT, () -> complexResourceService.copyFile(resource, handler, "a.txt", "b.txt", false, false, "user2"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.copyFile(resource, handler, "a.txt", "c.txt", false, false, "user2"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.copyFile(resource, handler, "SKILL.md", "c.txt", true, false, "user2"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.copyFile(resource, handler, "absent.txt", "c.txt", false, false, "user2"));
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.copyFile(resource, handler, "a.txt", "a.txt", true, true, "user2"));
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        complexResourceService.copyFile(resource, handler, "a.txt", "b.txt", true, true, "user2");
        assertNull(fileMetadata(resource, "a.txt"));
        assertEquals(2, complexResourceService.getMarker(resource).getFileMetadata().size());
    }

    @Test
    void testCopiedManifestValidatedAndRefreshedBeforeCommit() {
        ResourceDescriptor resource = skill("copy-manifest");
        putSkill(resource, EtagHeader.ANY);
        putFile(resource, "invalid.txt", "invalid manifest");
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.copyFile(resource, handler, "invalid.txt", "SKILL.md", false, true, "user2"));
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        String replacement = MANIFEST.replace("Test Skill", "Copied Skill");
        putFile(resource, "valid.txt", replacement);
        FileMetadata source = fileMetadata(resource, "valid.txt");
        complexResourceService.copyFile(resource, handler, "valid.txt", "SKILL.md", false, true, "user2");
        assertEquals("Copied Skill", complexResourceService.getMarker(resource).getMetadata().get("name"));
        assertEquals(source.getEtag(), fileMetadata(resource, "SKILL.md").getEtag());
        assertEquals("application/x-custom", fileMetadata(resource, "SKILL.md").getContentType());
    }

    @Test
    void testLockedImportSnapshotAndDeleteDoNotReenterLocks() {
        ResourceDescriptor resource = skill("locked-import");
        putSkill(resource, EtagHeader.ANY);
        ResourceDescriptor source = new ResourceDescriptor(ResourceTypes.FILE, "source.txt", List.of(), "public", "public/", false);
        FileMetadata sourceMetadata = resourceService.putFile(source, new byte[]{1, 2}, EtagHeader.ANY, "image/custom", "source-author");
        try (LockService.Lock sourceLock = resourceService.lockResource(source);
                LockService.Lock rootLock = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource))) {
            Mockito.clearInvocations(resourceService);
            assertNull(complexResourceService.getFileSnapshotLocked(resource, "missing.txt"));
            ComplexResourceService.FileMutationResult imported = complexResourceService.importFileLocked(resource, handler, "import.txt",
                    source, sourceMetadata, false, "import-author");
            assertEquals(sourceMetadata.getEtag(), imported.metadata().getEtag());
            assertEquals("image/custom", imported.metadata().getContentType());
            ComplexResourceService.LockedFileSnapshot snapshot = complexResourceService.getFileSnapshotLocked(resource, "import.txt");
            assertEquals(imported.metadata().getEtag(), snapshot.metadata().getEtag());
            assertTrue(snapshot.descriptor().getUrl().contains("/v/"));
            assertEquals(resource.getUrl() + "/files/import.txt", snapshot.metadata().getUrl());
            assertStatus(HttpStatus.CONFLICT, () -> complexResourceService.importFileLocked(resource, handler, "import.txt",
                    source, sourceMetadata, false, "import-author"));
            complexResourceService.validateFileDeleteLocked(resource, handler, "import.txt");
            complexResourceService.deleteFileLocked(resource, handler, "import.txt", "delete-author");
            assertNull(complexResourceService.getFileSnapshotLocked(resource, "import.txt"));
            Mockito.verify(resourceService, Mockito.never()).lockResource(source);
            Mockito.verify(resourceService, Mockito.never()).lockResource(ComplexResourceService.getMarkerLockDescriptor(resource));
            Mockito.verify(resourceService).copyResourceLocked(Mockito.eq(source), Mockito.any(), Mockito.any(), Mockito.eq(true));
            Mockito.verify(resourceService, Mockito.never()).getResourceBytesLocked(Mockito.eq(source), Mockito.any());
        }
    }

    @Test
    void testMutationResultCapturedBeforeUnlock() {
        ResourceDescriptor resource = skill("response-snapshot");
        putSkill(resource, EtagHeader.ANY);
        AtomicBoolean replaceOnUnlock = new AtomicBoolean(true);
        Mockito.doAnswer(invocation -> {
            LockService.Lock lock = (LockService.Lock) invocation.callRealMethod();
            return (LockService.Lock) () -> {
                lock.close();
                if (replaceOnUnlock.compareAndSet(true, false)) {
                    putFile(resource, "a.txt", "replacement");
                }
            };
        }).when(resourceService).lockResource(ComplexResourceService.getMarkerLockDescriptor(resource));
        ComplexResourceService.FileMutationResult response = complexResourceService.putFile(resource, handler, "a.txt", new byte[]{1},
                EtagHeader.ANY, "text/first", "first-author");
        FileMetadata latest = fileMetadata(resource, "a.txt");
        assertNotEquals(response.resourceEtag(), latest.getResource().etag());
        assertNotEquals(response.metadata().getEtag(), latest.getEtag());
        assertEquals(response.resourceEtag(), response.metadata().getResource().etag());
        assertEquals("text/first", response.metadata().getContentType());
        assertEquals("first-author", response.metadata().getAuthor());
        assertEquals(1, response.metadata().getContentLength());
    }

    @Test
    void testNewFileLimitsAndManifestValidation() {
        ResourceDescriptor resource = skill("new-write-limits");
        putSkill(resource, EtagHeader.ANY);
        putFile(resource, "a.txt", "a");
        putFile(resource, "b.txt", "b");
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        assertStatus(HttpStatus.BAD_REQUEST, () -> putFile(resource, "c.txt", "c"));
        assertStatus(HttpStatus.REQUEST_ENTITY_TOO_LARGE, () -> putFile(resource, "a.txt", "a".repeat(101)));
        assertStatus(HttpStatus.BAD_REQUEST, () -> putFile(resource, "SKILL.md", "invalid manifest"));
        assertStatus(HttpStatus.BAD_REQUEST,
                () -> complexResourceService.deleteFileWithFileEtag(resource, handler, "SKILL.md", EtagHeader.ANY, "user2"));
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        putFile(resource, "a.txt", "a".repeat(100));
        before = complexResourceService.getMarker(resource).getCurrentVersion();
        assertStatus(HttpStatus.REQUEST_ENTITY_TOO_LARGE, () -> putFile(resource, "b.txt", "b".repeat(100)));
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        assertEquals(100, fileMetadata(resource, "a.txt").getContentLength());
    }

    @Test
    void testLockedImportValidatesBeforeCopy() {
        ResourceDescriptor resource = skill("import-validation");
        putSkill(resource, EtagHeader.ANY);
        ResourceDescriptor source = new ResourceDescriptor(ResourceTypes.FILE, "source.txt", List.of(), "public", "public/", false);
        FileMetadata sourceMetadata = resourceService.putFile(source, new byte[101], EtagHeader.ANY, "text/custom", "user1");
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        try (LockService.Lock sourceLock = resourceService.lockResource(source);
                LockService.Lock rootLock = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource))) {
            Mockito.clearInvocations(resourceService);
            assertStatus(HttpStatus.REQUEST_ENTITY_TOO_LARGE, () -> complexResourceService.importFileLocked(resource, handler, "a.txt",
                    source, sourceMetadata, false, "user2"));
            Mockito.verify(resourceService, Mockito.never()).copyResourceLocked(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
            assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.validateFileDeleteLocked(resource, handler, "SKILL.md"));
        }
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        assertNotNull(resourceService.getResourceMetadata(source));
        ResourceDescriptor absent = skill("import-absent");
        assertNull(complexResourceService.getFileSnapshotLocked(absent, "a.txt"));
        HttpException failure = assertThrows(HttpException.class,
                () -> complexResourceService.importFileLocked(absent, handler, "a.txt", source, sourceMetadata, false, "user2"));
        assertEquals(HttpStatus.NOT_FOUND, failure.getStatus());
        assertTrue(failure.getMessage().contains("PUT /v2/" + absent.getUrl()));
    }

    @Test
    void testLockedImportManifestValidationAndRefresh() {
        ResourceDescriptor resource = skill("locked-manifest");
        putSkill(resource, EtagHeader.ANY);
        ResourceDescriptor source = new ResourceDescriptor(ResourceTypes.FILE, "manifest.txt", List.of(), "public", "public/", false);
        FileMetadata invalid = resourceService.putFile(source, "invalid".getBytes(StandardCharsets.UTF_8), EtagHeader.ANY, "text/custom", "user1");
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        try (LockService.Lock sourceLock = resourceService.lockResource(source);
                LockService.Lock rootLock = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource))) {
            Mockito.clearInvocations(resourceService);
            assertStatus(HttpStatus.BAD_REQUEST,
                    () -> complexResourceService.importFileLocked(resource, handler, "SKILL.md", source, invalid, true, "user2"));
            Mockito.verify(resourceService).getResourceBytesLocked(source, EtagHeader.ANY);
            Mockito.verify(resourceService, Mockito.never()).copyResourceLocked(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
            Mockito.verify(resourceService, Mockito.never()).lockResource(source);
        }
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        FileMetadata valid = resourceService.putFile(source, MANIFEST.replace("Test Skill", "Imported Skill").getBytes(StandardCharsets.UTF_8),
                EtagHeader.ANY, "text/custom", "user1");
        try (LockService.Lock sourceLock = resourceService.lockResource(source);
                LockService.Lock rootLock = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource))) {
            Mockito.clearInvocations(resourceService);
            ComplexResourceService.FileMutationResult imported = complexResourceService.importFileLocked(resource, handler, "SKILL.md",
                    source, valid, true, "user2");
            assertEquals(valid.getEtag(), imported.metadata().getEtag());
            assertEquals("text/custom", imported.metadata().getContentType());
            Mockito.verify(resourceService).getResourceBytesLocked(source, EtagHeader.ANY);
            Mockito.verify(resourceService, Mockito.never()).lockResource(source);
            Mockito.verify(resourceService, Mockito.never()).lockResource(ComplexResourceService.getMarkerLockDescriptor(resource));
        }
        assertEquals("Imported Skill", complexResourceService.getMarker(resource).getMetadata().get("name"));
    }

    @Test
    void testCopyOnWriteIgnoresDeletedPhysicalGhostsForBothOverloads() {
        for (boolean legacy : new boolean[]{false, true}) {
            ResourceDescriptor resource = skill(legacy ? "ghost-legacy" : "ghost-file");
            putSkill(resource, EtagHeader.ANY);
            FolderResourceMarker before = complexResourceService.getMarker(resource);
            ResourceDescriptor ghost = versionFile(resource, before.getCurrentVersion(), "ghost.txt");
            resourceService.putFile(ghost, new byte[]{1}, EtagHeader.ANY, "text/plain", "user1");
            resourceService.deleteResource(ghost, EtagHeader.ANY);
            // Recreate only the provider blob: its Redis record remains tombstoned and the marker excludes it.
            blobStorage.store(ghost.getAbsoluteFilePath(), "text/plain", null, Map.of(), new byte[]{1});
            assertFalse(before.getFileMetadata().containsKey("ghost.txt"));
            assertNull(resourceService.getResourceMetadata(ghost));
            ResourceFolderMetadata physical = resourceService.getFolderMetadata(ghost.getParent(), null, 100, true);
            assertTrue(physical.getItems().stream().anyMatch(item -> item.getDescriptor().equals(ghost)));
            Mockito.clearInvocations(resourceService);
            if (legacy) {
                complexResourceService.putFile(resource, handler, "a.txt", new byte[]{2}, EtagHeader.ANY, "user2");
            } else {
                putFile(resource, "a.txt", "a");
            }
            FolderResourceMarker after = complexResourceService.getMarker(resource);
            assertEquals(Set.of("SKILL.md", "a.txt"), after.getFileMetadata().keySet());
            assertNull(fileMetadata(resource, "ghost.txt"));
            assertNotNull(fileMetadata(resource, "SKILL.md"));
            assertNotNull(fileMetadata(resource, "a.txt"));
            Mockito.verify(resourceService, Mockito.never()).copyFolder(Mockito.any(), Mockito.any(), Mockito.anyBoolean());
            Mockito.verify(resourceService, Mockito.never()).copyResource(Mockito.eq(ghost), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
            Mockito.verify(resourceService, Mockito.never()).copyResourceLocked(Mockito.eq(ghost), Mockito.any(), Mockito.any(), Mockito.anyBoolean());
            if (legacy) {
                Mockito.verify(resourceService).copyResource(Mockito.eq(versionFile(resource, before.getCurrentVersion(), "SKILL.md")),
                        Mockito.any(), Mockito.isNull(), Mockito.eq(false));
            } else {
                Mockito.verify(resourceService).copyResourceLocked(Mockito.eq(versionFile(resource, before.getCurrentVersion(), "SKILL.md")),
                        Mockito.any(), Mockito.isNull(), Mockito.eq(false));
            }
        }
    }

    @Test
    void testCopyOnWriteDoesNotSkipMissingExpectedMembers() {
        ResourceDescriptor resource = skill("missing-member");
        putSkill(resource, EtagHeader.ANY);
        FolderResourceMarker before = complexResourceService.getMarker(resource);
        ResourceDescriptor expected = versionFile(resource, before.getCurrentVersion(), "SKILL.md");
        Mockito.doReturn(false).when(resourceService).copyResourceLocked(Mockito.eq(expected), Mockito.any(), Mockito.isNull(), Mockito.eq(false));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> putFile(resource, "a.txt", "a"));
        assertEquals("Can't copy resource file: " + resource.getUrl() + "/files/SKILL.md", failure.getMessage());
        assertEquals(before.getCurrentVersion(), complexResourceService.getMarker(resource).getCurrentVersion());
        assertNotNull(fileMetadata(resource, "SKILL.md"));
        assertNull(fileMetadata(resource, "a.txt"));
    }

    @Test
    void testMarkerMembershipHidesPhysicalGhostsAfterCacheInvalidation() {
        ResourceDescriptor resource = skill("read-ghosts");
        putSkill(resource, EtagHeader.ANY);
        FolderResourceMarker current = complexResourceService.getMarker(resource);
        List<ResourceDescriptor> ghosts = List.of(
                versionFile(resource, current.getCurrentVersion(), "removed.txt"),
                new ResourceDescriptor(ResourceTypes.SKILL, "hidden.txt",
                        List.of(resource.getName(), "v", current.getCurrentVersion(), "ghost-folder"), "public", "public/", false));
        for (ResourceDescriptor ghost : ghosts) {
            resourceService.putFile(ghost, new byte[]{1}, EtagHeader.ANY, "text/plain", "user1");
            resourceService.deleteResource(ghost, EtagHeader.ANY);
            blobStorage.store(ghost.getAbsoluteFilePath(), "text/plain", null, Map.of(), new byte[]{1});
            redis.getKeys().delete(RedisUtil.redisKey(ghost, resourceService.getPrefix()));
            assertNotNull(resourceService.getResourceMetadata(ghost));
        }
        for (String path : List.of("removed.txt", "ghost-folder/hidden.txt")) {
            assertNull(complexResourceService.getFileStream(resource, path, EtagHeader.ANY));
            assertNull(complexResourceService.getFileStream(resource, path, EtagHeader.fromHeader("*", null, "GET")));
            assertNull(complexResourceService.getFileStream(resource, path, EtagHeader.fromHeader("\"unknown\"", null, "GET")));
            assertNull(complexResourceService.getFileMetadata(resource, path, null, 100, false));
            try (LockService.Lock ignore = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource))) {
                assertNull(complexResourceService.getFileSnapshotLocked(resource, path));
            }
        }
        assertNull(complexResourceService.getFileMetadata(resource, "ghost-folder/", null, 100, false));
        assertNull(complexResourceService.getFileMetadata(resource, "ghost-folder/", null, 0, false));
        for (boolean recursive : new boolean[]{false, true}) {
            ResourceFolderMetadata listing = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "", null, 100, recursive);
            assertEquals(1, listing.getItems().size());
            assertEquals(resource.getUrl() + "/files/SKILL.md", listing.getItems().getFirst().getUrl());
        }
    }

    @Test
    void testFileListingUsesMarkerMembersDespiteRawProviderOmission() {
        ResourceDescriptor resource = skill("listing-omission");
        complexResourceService.put(resource, handler, Map.of("SKILL.md", Buffer.buffer(MANIFEST),
                "refs/a.txt", Buffer.buffer("a"), "refs/b.txt", Buffer.buffer("b")), EtagHeader.ANY, "user1");
        FolderResourceMarker marker = complexResourceService.getMarker(resource);
        ResourceDescriptor physicalFolder = new ResourceDescriptor(ResourceTypes.SKILL, "refs",
                List.of(resource.getName(), "v", marker.getCurrentVersion()), "public", "public/", true);
        ResourceDescriptor physicalA = new ResourceDescriptor(ResourceTypes.SKILL, "a.txt",
                List.of(resource.getName(), "v", marker.getCurrentVersion(), "refs"), "public", "public/", false);
        FileMetadata a = (FileMetadata) resourceService.getResourceMetadata(physicalA);
        Mockito.doReturn(new ResourceFolderMetadata(physicalFolder, List.of(a), null)).when(resourceService)
                .getFolderMetadata(Mockito.eq(physicalFolder), Mockito.any(), Mockito.anyInt(), Mockito.anyBoolean());
        assertEquals(1, resourceService.getFolderMetadata(physicalFolder, null, 100, false).getItems().size());
        assertTrue(marker.getFileMetadata().containsKey("refs/b.txt"));
        ResourceDescriptor other = skill("other-listing");
        complexResourceService.put(other, handler, Map.of("SKILL.md", Buffer.buffer(MANIFEST),
                "refs/a.txt", Buffer.buffer("a"), "refs/b.txt", Buffer.buffer("b")), EtagHeader.ANY, "user1");
        Mockito.clearInvocations(resourceService);
        ResourceFolderMetadata complete = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", null, 1000, false);
        assertEquals(List.of("a.txt", "b.txt"), complete.getItems().stream().map(MetadataBase::getName).toList());
        ResourceFolderMetadata first = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", null, 1, false);
        assertNotNull(first.getNextToken());
        ResourceFolderMetadata second = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", first.getNextToken(), 1, false);
        ResourceFolderMetadata replay = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", first.getNextToken(), 1000, false);
        assertEquals(List.of("b.txt"), second.getItems().stream().map(MetadataBase::getName).toList());
        assertEquals(second.getItems(), replay.getItems());
        assertNull(second.getNextToken());
        for (String invalid : List.of("0", "", Base58.encode("not-a-cursor".getBytes(StandardCharsets.UTF_8)))) {
            assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.getFileMetadata(resource, "refs/", invalid, 1, false));
        }
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.getFileMetadata(resource, "", first.getNextToken(), 1, false));
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.getFileMetadata(resource, "refs/", first.getNextToken(), 1, true));
        assertStatus(HttpStatus.BAD_REQUEST, () -> complexResourceService.getFileMetadata(other, "refs/", first.getNextToken(), 1, false));
        Mockito.verify(resourceService, Mockito.never()).getFolderMetadata(Mockito.any(), Mockito.any(), Mockito.anyInt(), Mockito.anyBoolean());
    }

    @Test
    void testFileListingFailsRatherThanDroppingExpectedMetadata() {
        ResourceDescriptor resource = skill("listing-missing-metadata");
        complexResourceService.put(resource, handler, Map.of("SKILL.md", Buffer.buffer(MANIFEST), "refs/a.txt", Buffer.buffer("a")),
                EtagHeader.ANY, "user1");
        FolderResourceMarker marker = complexResourceService.getMarker(resource);
        ResourceDescriptor expected = new ResourceDescriptor(ResourceTypes.SKILL, "a.txt",
                List.of(resource.getName(), "v", marker.getCurrentVersion(), "refs"), "public", "public/", false);
        Mockito.doReturn(null).when(resourceService).getResourceMetadataLocked(expected);
        HttpException failure = assertThrows(HttpException.class,
                () -> complexResourceService.getFileMetadata(resource, "refs/", null, 100, false));
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failure.getStatus());
        assertEquals("Metadata unavailable for resource file: " + resource.getUrl() + "/files/refs/a.txt", failure.getMessage());
    }

    @Test
    void testLegacyFileListingReconstructsMissingMap() throws IOException {
        ResourceDescriptor resource = skill("legacy-listing");
        complexResourceService.put(resource, handler, Map.of("SKILL.md", Buffer.buffer(MANIFEST),
                "refs/a.txt", Buffer.buffer("a"), "refs/b.txt", Buffer.buffer("b")), EtagHeader.ANY, "user1");
        FolderResourceMarker marker = complexResourceService.getMarker(resource);
        marker.setFileMetadata(null);
        resourceService.putResource(ComplexResourceService.getMarkerLockDescriptor(resource), new ObjectMapper().writeValueAsString(marker),
                EtagHeader.ANY, "user1");
        ResourceFolderMetadata first = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", null, 1, false);
        ResourceFolderMetadata second = (ResourceFolderMetadata) complexResourceService.getFileMetadata(resource, "refs/", first.getNextToken(), 1, false);
        assertEquals("a.txt", first.getItems().getFirst().getName());
        assertEquals("b.txt", second.getItems().getFirst().getName());
        assertNotNull(((FileMetadata) second.getItems().getFirst()).getEtag());
        assertNull(second.getNextToken());
    }

    @Test
    void testProjectOverlappingPathMovesUseFinalSet() throws IOException {
        ComplexResourceService.Settings limits = new ComplexResourceService.Settings();
        limits.setTypes(Map.of(ResourceTypes.PROJECT.group(), new ComplexResourceService.Limits(1, 1L, 1L)));
        complexResourceService = new ComplexResourceService(resourceService, new LockService(redis, null), blobStorage, limits);
        ResourceDescriptor project = new ResourceDescriptor(ResourceTypes.PROJECT, "overlapping-move", List.of(), "public", "public/", false);
        ProjectHandler projectHandler = new ProjectHandler();
        complexResourceService.put(project, projectHandler, Map.of("a", Buffer.buffer("x")), EtagHeader.ANY, "user1");
        FileMetadata original = fileMetadata(project, "a");
        for (List<String> paths : List.of(List.of("a", "a/b"), List.of("a/b", "a"))) {
            String before = complexResourceService.getMarker(project).getCurrentVersion();
            Mockito.clearInvocations(resourceService);
            String etag = complexResourceService.copyFile(project, projectHandler, paths.getFirst(), paths.getLast(), true, false, "move-author");
            Mockito.verify(resourceService, Mockito.times(1)).putResource(Mockito.eq(ComplexResourceService.getMarkerLockDescriptor(project)),
                    Mockito.anyString(), Mockito.any(), Mockito.eq("move-author"), Mockito.eq(false));
            Mockito.verify(resourceService, Mockito.times(1)).publishLogicalEvent(Mockito.eq(project), Mockito.eq(ResourceEvent.Action.UPDATE),
                    Mockito.anyLong(), Mockito.eq(etag));
            FolderResourceMarker after = complexResourceService.getMarker(project);
            assertNotEquals(before, after.getCurrentVersion());
            assertEquals(Set.of(paths.getLast()), after.getFileMetadata().keySet());
            assertNull(fileMetadata(project, paths.getFirst()));
            FileMetadata moved = fileMetadata(project, paths.getLast());
            assertEquals(original.getEtag(), moved.getEtag());
            assertEquals(original.getContentType(), moved.getContentType());
            assertEquals(1, moved.getContentLength());
            try (ResourceService.ResourceStream stream = complexResourceService.getFileStream(project, paths.getLast(), EtagHeader.ANY).stream()) {
                assertEquals("x", new String(stream.inputStream().readAllBytes(), StandardCharsets.UTF_8));
            }
        }
    }

    @Test
    void testPutReplacementKeepsFileAuthorAndCreatedAt() {
        ResourceDescriptor resource = skill("put-original-metadata");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata original = putFile(resource, "a.txt", "a");
        FileMetadata replacement = complexResourceService.putFile(resource, handler, "a.txt", new byte[]{2}, EtagHeader.ANY,
                "text/replacement", "other-author").metadata();
        assertEquals(original.getAuthor(), replacement.getAuthor());
        assertEquals(original.getCreatedAt(), replacement.getCreatedAt());
        complexResourceService.putFile(resource, handler, "a.txt", new byte[]{3}, EtagHeader.ANY, "legacy-author");
        FileMetadata legacy = fileMetadata(resource, "a.txt");
        assertEquals(original.getAuthor(), legacy.getAuthor());
        assertEquals(original.getCreatedAt(), legacy.getCreatedAt());
    }

    @Test
    void testAppliedMarkerWriteWithLostAcknowledgementRetainsCommittedVersion() throws IOException {
        ResourceDescriptor resource = skill("lost-marker-ack");
        putSkill(resource, EtagHeader.ANY);
        putFile(resource, "other.txt", "other");
        FolderResourceMarker beforeMarker = complexResourceService.getMarker(resource);
        String before = beforeMarker.getCurrentVersion();
        Mockito.doAnswer(invocation -> {
            invocation.callRealMethod();
            throw new IllegalStateException("lost marker acknowledgement");
        }).when(resourceService).putResource(Mockito.eq(ComplexResourceService.getMarkerLockDescriptor(resource)),
                Mockito.anyString(), Mockito.any(), Mockito.eq("writer"), Mockito.eq(false));
        assertThrows(IllegalStateException.class, () -> putFile(resource, "new.txt", "new"));
        FolderResourceMarker committed = complexResourceService.getMarker(resource);
        assertNotEquals(before, committed.getCurrentVersion());
        assertEquals(Set.of("SKILL.md", "other.txt", "new.txt"), committed.getFileMetadata().keySet());
        assertNotNull(fileMetadata(resource, "SKILL.md"));
        assertNotNull(fileMetadata(resource, "other.txt"));
        assertNotNull(fileMetadata(resource, "new.txt"));
        Mockito.doReturn(new ObjectMapper().writeValueAsString(beforeMarker)).when(resourceService)
                .getResource(ComplexResourceService.getMarkerLockDescriptor(resource), EtagHeader.ANY, false);
        assertTrue(complexResourceService.gcObsoleteVersions(resource, 0));
        assertNotNull(fileMetadata(resource, "other.txt"));
        assertNotNull(fileMetadata(resource, "new.txt"));
    }

    @Test
    void testUnappliedMarkerWriteRetainsOrphanForGc() {
        ResourceDescriptor resource = skill("unapplied-marker");
        putSkill(resource, EtagHeader.ANY);
        putFile(resource, "other.txt", "other");
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        Mockito.clearInvocations(resourceService);
        Mockito.doThrow(new IllegalStateException("marker not applied")).when(resourceService).putResource(
                Mockito.eq(ComplexResourceService.getMarkerLockDescriptor(resource)), Mockito.anyString(), Mockito.any(), Mockito.eq("writer"), Mockito.eq(false));
        assertThrows(IllegalStateException.class, () -> putFile(resource, "new.txt", "new"));
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        assertNull(fileMetadata(resource, "new.txt"));
        assertNotNull(fileMetadata(resource, "other.txt"));
        ArgumentCaptor<ResourceDescriptor> uploaded = ArgumentCaptor.forClass(ResourceDescriptor.class);
        Mockito.verify(resourceService).putFile(uploaded.capture(), Mockito.any(byte[].class), Mockito.any(),
                Mockito.eq("application/x-custom"), Mockito.eq("writer"));
        assertNotNull(resourceService.getResourceMetadataLocked(uploaded.getValue()));
        assertTrue(complexResourceService.gcObsoleteVersions(resource, 0));
        assertNull(resourceService.getResourceMetadataLocked(uploaded.getValue()));
        assertNotNull(fileMetadata(resource, "SKILL.md"));
        assertNotNull(fileMetadata(resource, "other.txt"));
    }

    @Test
    void testNewLockedReadsUseMasterSnapshot() throws IOException {
        ResourceDescriptor resource = skill("master-snapshot");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata stale = putFile(resource, "a.txt", "old");
        FolderResourceMarker oldMarker = complexResourceService.getMarker(resource);
        FileMetadata latest = putFile(resource, "a.txt", "latest");
        FolderResourceMarker latestMarker = complexResourceService.getMarker(resource);
        ResourceDescriptor marker = ComplexResourceService.getMarkerLockDescriptor(resource);
        ResourceDescriptor physical = versionFile(resource, latestMarker.getCurrentVersion(), "a.txt");
        Mockito.doReturn(new ObjectMapper().writeValueAsString(oldMarker)).when(resourceService).getResource(marker, EtagHeader.ANY, false);
        Mockito.doReturn(stale).when(resourceService).getResourceMetadata(physical);
        Mockito.clearInvocations(resourceService);
        assertStatus(HttpStatus.PRECONDITION_FAILED, () -> complexResourceService.putFile(resource, handler, "a.txt", new byte[]{3},
                EtagHeader.fromHeader(stale.getEtag(), null, "PUT"), "text/plain", "user2"));
        try (ResourceService.ResourceStream stream = complexResourceService.getFileStream(resource, "a.txt",
                EtagHeader.fromHeader(latest.getEtag(), null, "GET")).stream()) {
            assertEquals(latest.getEtag(), stream.etag());
            assertEquals("latest", new String(stream.inputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(latest.getEtag(), fileMetadata(resource, "a.txt").getEtag());
        try (LockService.Lock ignore = resourceService.lockResource(marker)) {
            ComplexResourceService.LockedFileSnapshot snapshot = complexResourceService.getFileSnapshotLocked(resource, "a.txt");
            assertEquals(latest.getEtag(), snapshot.metadata().getEtag());
            assertEquals(latestMarker.getEtag(), snapshot.metadata().getResource().etag());
        }
        Mockito.verify(resourceService, Mockito.never()).getResource(marker, EtagHeader.ANY, false);
        Mockito.verify(resourceService, Mockito.never()).getResourceMetadata(physical);
        Mockito.verify(resourceService, Mockito.atLeastOnce()).getResourceLocked(marker, EtagHeader.ANY);
        Mockito.verify(resourceService, Mockito.atLeastOnce()).getResourceMetadataLocked(physical);
    }

    @Test
    void testCopiedActualSizeRevalidatedAfterImport() {
        ResourceDescriptor resource = skill("actual-copy-size");
        putSkill(resource, EtagHeader.ANY);
        ResourceDescriptor source = new ResourceDescriptor(ResourceTypes.FILE, "large-source.txt", List.of(), "public", "public/", false);
        FileMetadata actual = resourceService.putFile(source, new byte[101], EtagHeader.ANY, "text/plain", "user1");
        FileMetadata staleSize = new FileMetadata(source, 1, actual.getContentType());
        staleSize.setEtag(actual.getEtag());
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        try (LockService.Lock sourceLock = resourceService.lockResource(source);
                LockService.Lock markerLock = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource))) {
            assertStatus(HttpStatus.REQUEST_ENTITY_TOO_LARGE,
                    () -> complexResourceService.importFileLocked(resource, handler, "a.txt", source, staleSize, false, "user2"));
        }
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        assertNull(fileMetadata(resource, "a.txt"));
        assertEquals(101, resourceService.getResourceMetadataLocked(source) instanceof FileMetadata file ? file.getContentLength() : -1);
        assertNotNull(fileMetadata(resource, "SKILL.md"));
    }

    @Test
    void testCopiedManifestValidationUsesMasterBytes() {
        ResourceDescriptor resource = skill("master-manifest");
        putSkill(resource, EtagHeader.ANY);
        ResourceDescriptor source = new ResourceDescriptor(ResourceTypes.FILE, "source-manifest.txt", List.of(), "public", "public/", false);
        FileMetadata stale = resourceService.putFile(source, MANIFEST.getBytes(StandardCharsets.UTF_8), EtagHeader.ANY, "text/plain", "user1");
        resourceService.putFile(source, "invalid".getBytes(StandardCharsets.UTF_8), EtagHeader.ANY, "text/plain", "user1");
        Mockito.doReturn(stale).when(resourceService).getResourceMetadata(source);
        Mockito.doReturn(MANIFEST.getBytes(StandardCharsets.UTF_8)).when(resourceService).getResourceBytes(source, EtagHeader.ANY, false);
        String before = complexResourceService.getMarker(resource).getCurrentVersion();
        try (LockService.Lock sourceLock = resourceService.lockResource(source);
                LockService.Lock markerLock = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource))) {
            assertStatus(HttpStatus.BAD_REQUEST,
                    () -> complexResourceService.importFileLocked(resource, handler, "SKILL.md", source, stale, true, "user2"));
        }
        assertEquals(before, complexResourceService.getMarker(resource).getCurrentVersion());
        Mockito.verify(resourceService).getResourceBytesLocked(source, EtagHeader.ANY);
        assertNotNull(fileMetadata(resource, "SKILL.md"));
    }

    @Test
    void testLockedRestorePreservesSavedFileMetadata() {
        ResourceDescriptor resource = skill("restore-metadata");
        putSkill(resource, EtagHeader.ANY);
        FileMetadata saved = putFile(resource, "a.txt", "a");
        ResourceDescriptor backup = new ResourceDescriptor(ResourceTypes.FILE, "backup.txt", List.of(), "public", "public/", false);
        resourceService.putFile(backup, "a".getBytes(StandardCharsets.UTF_8), EtagHeader.ANY, saved.getContentType(), "backup-author");
        try (LockService.Lock markerLock = resourceService.lockResource(ComplexResourceService.getMarkerLockDescriptor(resource));
                LockService.Lock backupLock = resourceService.lockResource(backup)) {
            Mockito.clearInvocations(resourceService);
            complexResourceService.deleteFileLocked(resource, handler, "a.txt", "delete-author");
            assertNull(complexResourceService.getFileSnapshotLocked(resource, "a.txt"));
            ComplexResourceService.FileMutationResult restored = complexResourceService.restoreFileLocked(resource, handler, "a.txt", backup, saved);
            FileMetadata metadata = restored.metadata();
            assertEquals(saved.getEtag(), metadata.getEtag());
            assertEquals(saved.getAuthor(), metadata.getAuthor());
            assertEquals(saved.getCreatedAt(), metadata.getCreatedAt());
            assertEquals(saved.getUpdatedAt(), metadata.getUpdatedAt());
            assertEquals(saved.getContentType(), metadata.getContentType());
            assertEquals(saved.getContentLength(), metadata.getContentLength());
            assertEquals(saved.getUrl(), metadata.getUrl());
            assertEquals(saved.getResource().etag(), restored.resourceEtag());
            assertNotNull(complexResourceService.getFileSnapshotLocked(resource, "SKILL.md"));
            assertEquals(metadata.getEtag(), complexResourceService.getFileSnapshotLocked(resource, "a.txt").metadata().getEtag());
            Mockito.verify(resourceService, Mockito.never()).lockResource(ComplexResourceService.getMarkerLockDescriptor(resource));
            Mockito.verify(resourceService, Mockito.never()).lockResource(backup);
            ArgumentCaptor<UserMetadata> copied = ArgumentCaptor.forClass(UserMetadata.class);
            Mockito.verify(resourceService).copyResourceLocked(Mockito.eq(backup), Mockito.any(), copied.capture(), Mockito.eq(true));
            assertEquals(saved.getCreatedAt(), copied.getValue().getCreatedAt());
            assertEquals(saved.getUpdatedAt(), copied.getValue().getUpdatedAt());
            assertEquals(saved.getAuthor(), copied.getValue().getAuthor());
        }
    }

    private static void assertThrowsBadRequest(Runnable action) {
        HttpException e = assertThrows(HttpException.class, action::run);
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
    }
}
