package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.blobstore.BlobStorage;
import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

class ResourceTopicServiceWiringTest {

    private static RedisServer server;
    private static RedissonClient client;
    private static ResourceService service;

    @BeforeAll
    static void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16374)
                    .bind("127.0.0.1")
                    .setting("maxmemory 4M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16374");
            client = Redisson.create(config);

            TimerService timerService = Mockito.mock(TimerService.class);
            BlobStorage blobStorage = Mockito.mock(BlobStorage.class);
            LockService lockService = new LockService(client, null);
            ResourceService.Settings settings = new ResourceService.Settings(
                    64 * 1024 * 1024, 1024 * 1024, 60_000, 120_000, 4096, 300_000, 256);
            service = new ResourceService(timerService, client, blobStorage, lockService, settings, null);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterAll
    static void destroy() throws IOException {
        try {
            if (client != null) {
                client.shutdown();
            }
        } finally {
            if (server != null) {
                server.stop();
            }
        }
    }

    @Test
    void serviceTopicIgnoresUnknownFields() throws InterruptedException {
        ResourceTopic topic = service.getTopic();
        Assertions.assertNotNull(topic);

        ResourceDescriptor descriptor = new ResourceDescriptor(
                ResourceTypes.APPLICATION, "wiring-test-app", List.of(), "public", "public/", false);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ResourceEvent> received = new AtomicReference<>();
        try (ResourceTopic.Subscription ignored = service.subscribeResources(List.of(descriptor), event -> {
            received.set(event);
            latch.countDown();
        })) {
            String topicKey = "resource:topic";
            String json = "{\"url\":\"" + descriptor.getUrl() + "\","
                    + "\"action\":\"UPDATE\","
                    + "\"timestamp\":7,"
                    + "\"etag\":\"xyz\","
                    + "\"senderPodId\":\"pod-y\"}";
            client.getTopic(topicKey, StringCodec.INSTANCE).publish(json);

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "ResourceService.getTopic() must tolerate unknown senderPodId field");
            ResourceEvent event = received.get();
            Assertions.assertNotNull(event);
            Assertions.assertEquals(descriptor.getUrl(), event.getUrl());
            Assertions.assertEquals(ResourceEvent.Action.UPDATE, event.getAction());
            Assertions.assertEquals(7L, event.getTimestamp());
            Assertions.assertEquals("xyz", event.getEtag());
        }
    }
}
