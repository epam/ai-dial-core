package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.FileMetadata;
import com.epam.aidial.core.storage.data.ResourceUpload;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;

import java.nio.file.Path;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

public class ResourceServiceFileUploadTest {

    private static final int PART_SIZE_BYTES = 5 * 1024 * 1024;
    private static final int REDIS_PORT = 16371;

    private redis.embedded.RedisServer redisServer;
    private RedissonClient redisson;
    private BlobStorage blobStore;
    private ResourceService resourceService;
    private Path testDir;

    @BeforeEach
    void init() throws Exception {
        testDir = FileUtil.baseTestPath(ResourceServiceFileUploadTest.class);
        FileUtil.createDir(testDir);

        redisServer = redis.embedded.RedisServer.newRedisServer()
                .port(REDIS_PORT)
                .bind("127.0.0.1")
                .onShutdownForceStop(true)
                .setting("maxmemory 16M")
                .setting("maxmemory-policy volatile-lfu")
                .build();
        redisServer.start();

        JsonNode redisConfig = new ObjectMapper().readTree("""
                {
                  "singleServerConfig": {
                    "address": "redis://localhost:%d"
                  }
                }
                """.formatted(REDIS_PORT));
        redisson = com.epam.aidial.core.storage.cache.CacheClientFactory.create(redisConfig);

        Properties overrides = new Properties();
        overrides.put("jclouds.filesystem.basedir", testDir.toString());
        Storage storageConfig = new Storage();
        storageConfig.setProvider("filesystem");
        storageConfig.setIdentity("access-key");
        storageConfig.setCredential("secret-key");
        storageConfig.setBucket("test");
        storageConfig.setCreateBucket(true);
        storageConfig.setOverrides(overrides);
        blobStore = spy(new BlobStorage(storageConfig));

        LockService lockService = new LockService(redisson, null);
        // no-op timer so the background sync never runs and cannot interfere with assertions
        TimerService timerService = (initialDelay, delay, task) -> () -> {
        };
        ResourceService.Settings settings = new ResourceService.Settings(
                512 * 1024 * 1024, // maxSize
                1024 * 1024,       // maxSizeToCache - small file is cached, big file is not
                600_000,           // syncPeriod
                600_000,           // syncDelay
                10,                // syncBatch
                600_000,           // cacheExpiration
                256);              // compressionMinSize

        resourceService = new ResourceService(timerService, redisson, blobStore, lockService, settings, null);
    }

    @AfterEach
    void clean() throws Exception {
        if (resourceService != null) {
            resourceService.close();
        }
        if (redisson != null) {
            redisson.shutdown();
        }
        if (redisServer != null) {
            redisServer.stop();
        }
        if (testDir != null) {
            FileUtil.deleteDir(testDir);
        }
    }

    /**
     * Reproduces issue #1594: a small file (single blob, cached in Redis) overwritten by a large file (multipart upload)
     * must not have the stale cached version flushed back to the blob store while the multipart upload is finalized.
     */
    @Test
    void overwritingSmallFileWithMultipartUploadDoesNotFlushStaleContent() throws Exception {
        ResourceDescriptor file = new ResourceDescriptor(
                ResourceTypes.FILE, "1.json", List.of(), "bucket", "buckets/bucket/", false);

        byte[] smallContent = new byte[1024];
        java.util.Arrays.fill(smallContent, (byte) 1);
        resourceService.putFile(file, smallContent, EtagHeader.ANY, "application/json", null);

        // ignore the blob writes made by the small upload (an empty stub for listing)
        clearInvocations(blobStore);

        byte[] bigContent = new byte[(int) (PART_SIZE_BYTES * 1.5)];
        for (int i = 0; i < bigContent.length; i++) {
            bigContent[i] = (byte) i;
        }

        ResourceUpload upload = resourceService.initFileUpload(file, "application/octet-stream", EtagHeader.ANY, null);
        upload.addChunk(Unpooled.wrappedBuffer(bigContent));
        FileMetadata metadata = resourceService.finishFileUpload(file, upload, EtagHeader.ANY);

        // finishing the multipart upload must NOT write the stale cached body back to the blob store
        verify(blobStore, never()).store(any(), any(), any(), any(), any());

        assertEquals(bigContent.length, metadata.getContentLength());

        try (ResourceService.ResourceStream stream = resourceService.getResourceStream(file, EtagHeader.ANY)) {
            assertEquals(bigContent.length, stream.contentLength());
            assertArrayEquals(bigContent, stream.inputStream().readAllBytes());
        }
    }
}
