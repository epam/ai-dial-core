package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.epam.aidial.core.storage.util.RedisUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceServiceInvalidateTest {

    private RedisServer redisServer;
    private RedissonClient redis;
    private BlobStorage storage;
    private java.nio.file.Path testDir;
    private LockService lockService;
    private String prefix;

    @AfterEach
    void destroy() throws IOException, InterruptedException {
        try {
            if (redis != null) {
                redis.shutdown();
            }
            if (storage != null) {
                storage.close();
            }
        } finally {
            if (redisServer != null) {
                redisServer.stop();
            }
            if (testDir != null) {
                deleteDirWithRetry(testDir);
            }
        }
    }

    // The filesystem provider can leave a memory-mapped file handle that Windows releases only after a GC.
    private static void deleteDirWithRetry(java.nio.file.Path dir) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                FileUtil.deleteDir(dir);
                return;
            } catch (Exception e) {
                System.gc();
                Thread.sleep(50);
            }
        }
        FileUtil.deleteDir(dir);
    }

    private ResourceService init(String storagePrefix) throws IOException {
        this.prefix = storagePrefix;
        int redisPort = freePort();
        redisServer = RedisServer.newRedisServer().port(redisPort).bind("127.0.0.1")
                .setting("maxmemory 8M").setting("maxmemory-policy volatile-lfu").build();
        redisServer.start();

        Config config = new Config();
        config.useSingleServer().setAddress("redis://localhost:" + redisPort);
        redis = Redisson.create(config);

        testDir = FileUtil.baseTestPath(ResourceServiceInvalidateTest.class);
        FileUtil.deleteDir(testDir);
        FileUtil.createDir(testDir.resolve("test"));
        ObjectMapper mapper = new ObjectMapper();
        String blobStorageConfig = """
                {
                    "bucket": "test",
                    "provider": "filesystem",
                    "identity": "access-key",
                    "credential": "secret-key",
                    "overrides": {
                      "jclouds.filesystem.basedir": %s
                    }
                  }
                """.formatted(mapper.writeValueAsString(testDir.toString()));
        storage = new BlobStorage(mapper.readValue(blobStorageConfig, Storage.class));

        lockService = new LockService(redis, storagePrefix);
        TimerService timerService = Mockito.mock(TimerService.class);
        String serviceConfig = """
                {
                 "maxSize" : 67108864,
                 "maxSizeToCache": 1048576,
                 "syncPeriod": 60000,
                 "syncDelay": 3600000,
                 "syncBatch": 4096,
                 "cacheExpiration": 300000,
                 "compressionMinSize": 256,
                 "heartbeatPeriod": 60000
                }
                """;
        ResourceService.Settings settings = mapper.readValue(serviceConfig, ResourceService.Settings.class);
        return new ResourceService(timerService, redis, storage, lockService, settings, storagePrefix);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private ResourceDescriptor resource(ResourceTypes type, String bucketLocation, String name) {
        String bucket = bucketLocation.substring(0, bucketLocation.length() - 1);
        return new ResourceDescriptor(type, name, List.of(), bucket, bucketLocation, false);
    }

    private boolean isCached(ResourceDescriptor descriptor) {
        return redis.getMap(RedisUtil.redisKey(descriptor, prefix)).isExists();
    }

    private boolean isQueued(ResourceDescriptor descriptor) {
        String queueKey = "resource:" + BlobStorageUtil.toStoragePath(prefix, "queue");
        return redis.getScoredSortedSet(queueKey, StringCodec.INSTANCE).contains(RedisUtil.redisKey(descriptor, prefix));
    }

    @Test
    void testQueuedEntriesOfTheBucketsAreRemoved() throws IOException {
        ResourceService service = init(null);
        ResourceDescriptor publicRole = resource(ResourceTypes.ROLE, "public/", "r1");
        ResourceDescriptor platformRole = resource(ResourceTypes.ROLE, "platform/", "r2");
        service.putResource(publicRole, "{}", EtagHeader.NEW_ONLY);
        service.putResource(platformRole, "{}", EtagHeader.NEW_ONLY);
        assertTrue(isQueued(publicRole));
        assertTrue(isQueued(platformRole));

        service.invalidate(List.of("public/", "platform/"));

        assertFalse(isQueued(publicRole));
        assertFalse(isQueued(platformRole));
    }

    @Test
    void testCachedEntriesOfTheBucketsAreRemoved() throws IOException {
        ResourceService service = init(null);
        ResourceDescriptor role = resource(ResourceTypes.ROLE, "public/", "r1");
        service.putResource(role, "{}", EtagHeader.NEW_ONLY);
        assertTrue(isCached(role));

        service.invalidate(List.of("public/", "platform/"));

        assertFalse(isCached(role));
    }

    @Test
    void testCachedCekIsRemoved() throws IOException {
        ResourceService service = init(null);
        ResourceDescriptor cek = resource(ResourceTypes.ENCRYPTION_KEYS, "platform/", "cek");
        service.putResource(cek, "{}", EtagHeader.NEW_ONLY);
        assertTrue(isCached(cek));

        service.invalidate(List.of("public/", "platform/"));

        assertFalse(isCached(cek));
    }

    @Test
    void testUsersBucketKeysAreKept() throws IOException {
        ResourceService service = init(null);
        ResourceDescriptor file = resource(ResourceTypes.FILE, "Users/alice/", "a.txt");
        service.putResource(file, "x", EtagHeader.NEW_ONLY);
        assertTrue(isCached(file));

        service.invalidate(List.of("public/", "platform/"));

        assertTrue(isCached(file), "a key outside public/ and platform/ must survive");
    }

    @Test
    void testApiKeyDataKeysAreKept() throws IOException {
        ResourceService service = init(null);
        ResourceDescriptor apiKeyData = new ResourceDescriptor(
                ResourceTypes.API_KEY_DATA, "k1", List.of(), "api_key_data", "api_key_data/", false);
        service.putResource(apiKeyData, "{}", EtagHeader.NEW_ONLY);
        assertTrue(isCached(apiKeyData));

        service.invalidate(List.of("public/", "platform/"));

        assertTrue(isCached(apiKeyData), "api_key_data keys live under their own bucket and must be kept");
    }

    @Test
    void testLockKeysAreKept() throws IOException {
        ResourceService service = init(null);
        String bucketLockKey = BlobStorageUtil.toStoragePath(null, "public/");
        ResourceDescriptor file = resource(ResourceTypes.FILE, "platform/", "doc");

        LockService.Lock bucketLock = lockService.tryLock(bucketLockKey);
        LockService.Lock fileLock = service.tryLockResource(file);
        assertNotNull(bucketLock);
        assertNotNull(fileLock);
        try {
            service.invalidate(List.of("public/", "platform/"));

            assertNull(lockService.tryLock(bucketLockKey), "the bucket lock must still be held after invalidate");
            assertNull(service.tryLockResource(file), "the per-resource lock must still be held after invalidate");
        } finally {
            bucketLock.close();
            fileLock.close();
        }
    }

    @Test
    void testWorksWithStoragePrefix() throws IOException {
        ResourceService service = init("my-prefix");
        ResourceDescriptor role = resource(ResourceTypes.ROLE, "public/", "r1");
        service.putResource(role, "{}", EtagHeader.NEW_ONLY);
        assertTrue(isCached(role));
        assertTrue(isQueued(role));

        service.invalidate(List.of("public/", "platform/"));

        assertFalse(isCached(role));
        assertFalse(isQueued(role));
    }

    @Test
    void testServiceReadsTheBlobAfterInvalidation() throws IOException {
        ResourceService service = init(null);
        ResourceDescriptor role = resource(ResourceTypes.ROLE, "platform/", "r1");
        service.putResource(role, "{\"v\":\"old\"}", EtagHeader.NEW_ONLY);
        assertArrayEquals("{\"v\":\"old\"}".getBytes(StandardCharsets.UTF_8), service.getResourceBytes(role));

        // the replace phase of a system import writes the new content straight to blob storage
        storage.store(role.getAbsoluteFilePath(), "application/json", null, Map.of(), "{\"v\":\"new\"}".getBytes(StandardCharsets.UTF_8));
        service.invalidate(List.of("public/", "platform/"));

        assertArrayEquals("{\"v\":\"new\"}".getBytes(StandardCharsets.UTF_8), service.getResourceBytes(role));
    }

    @Test
    void testQueuedWriteIsNotFlushedAfterInvalidation() throws IOException {
        ResourceService service = init(null);
        ResourceDescriptor role = resource(ResourceTypes.ROLE, "platform/", "r1");
        service.putResource(role, "{\"v\":\"queued\"}", EtagHeader.NEW_ONLY);
        assertTrue(isQueued(role));

        // the replace phase of a system import writes the imported content straight to blob storage
        storage.store(role.getAbsoluteFilePath(), "application/json", null, Map.of(), "{\"v\":\"imported\"}".getBytes(StandardCharsets.UTF_8));
        service.invalidate(List.of("public/", "platform/"));
        service.sync();

        byte[] bytes;
        try (var in = storage.load(role.getAbsoluteFilePath()).getPayload().openStream()) {
            bytes = in.readAllBytes();
        }
        assertArrayEquals("{\"v\":\"imported\"}".getBytes(StandardCharsets.UTF_8), bytes);
    }
}
