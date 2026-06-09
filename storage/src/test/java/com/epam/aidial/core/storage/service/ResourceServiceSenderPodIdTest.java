package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.FileUtil;
import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.blobstore.Storage;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class ResourceServiceSenderPodIdTest {

    private RedisServer server;
    private RedissonClient client;
    private BlobStorage storage;
    private Path testDir;

    @BeforeEach
    void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16376)
                    .bind("127.0.0.1")
                    .setting("maxmemory 8M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16376");
            client = Redisson.create(config);

            testDir = FileUtil.baseTestPath(ResourceServiceSenderPodIdTest.class);
            FileUtil.createDir(testDir.resolve("test"));
            String blobStorageConfig = """
                    {
                        "bucket": "test",
                        "provider": "filesystem",
                        "identity": "access-key",
                        "credential": "secret-key",
                        "prefix": "test-pod-id",
                        "overrides": {
                          "jclouds.filesystem.basedir": "%s"
                        }
                      }
                    """.formatted(testDir.toString());
            ObjectMapper mapper = new ObjectMapper();
            Storage storageConfig = mapper.readValue(blobStorageConfig, Storage.class);
            storage = new BlobStorage(storageConfig);
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
            if (storage != null) {
                storage.close();
            }
        } finally {
            if (server != null) {
                server.stop();
            }
            FileUtil.deleteDir(testDir);
        }
    }

    private ResourceService newService(java.util.function.Supplier<String> supplier) {
        TimerService timerService = Mockito.mock(TimerService.class);
        LockService lockService = new LockService(client, null);
        ResourceService.Settings settings = new ResourceService.Settings(
                64 * 1024 * 1024, 1024 * 1024, 60_000, 120_000, 4096, 300_000, 256);
        return new ResourceService(timerService, client, storage, lockService, settings, null, supplier);
    }

    @Test
    void publishStampsSenderPodIdFromSupplier() throws InterruptedException {
        ResourceService service = newService(() -> "pod-alpha");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ResourceEvent> received = new AtomicReference<>();
        try (ResourceTopic.Subscription ignored = service.getTopic().subscribeAll(event -> {
            received.set(event);
            latch.countDown();
        })) {
            ResourceDescriptor descriptor = new ResourceDescriptor(
                    ResourceTypes.APPLICATION, "pod-id-app", List.of(), "public", "public/", false);
            service.putResource(descriptor, "{}", EtagHeader.ANY);

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals("pod-alpha", received.get().getSenderPodId());
        }
    }

    @Test
    void legacyConstructorEmitsNullSenderPodId() throws InterruptedException {
        TimerService timerService = Mockito.mock(TimerService.class);
        LockService lockService = new LockService(client, null);
        ResourceService.Settings settings = new ResourceService.Settings(
                64 * 1024 * 1024, 1024 * 1024, 60_000, 120_000, 4096, 300_000, 256);
        ResourceService service = new ResourceService(timerService, client, storage, lockService, settings, null);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ResourceEvent> received = new AtomicReference<>();
        try (ResourceTopic.Subscription ignored = service.getTopic().subscribeAll(event -> {
            received.set(event);
            latch.countDown();
        })) {
            ResourceDescriptor descriptor = new ResourceDescriptor(
                    ResourceTypes.APPLICATION, "legacy-app", List.of(), "public", "public/", false);
            service.putResource(descriptor, "{}", EtagHeader.ANY);

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assertions.assertNull(received.get().getSenderPodId());
        }
    }
}
