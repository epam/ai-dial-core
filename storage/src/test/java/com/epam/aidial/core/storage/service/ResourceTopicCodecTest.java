package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.data.ResourceEvent;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

class ResourceTopicCodecTest {

    private static RedisServer server;
    private static RedissonClient client;

    @BeforeAll
    static void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16373)
                    .bind("127.0.0.1")
                    .setting("maxmemory 4M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16373");
            client = Redisson.create(config);
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
    void defaultConstructorIgnoresUnknownFields() throws InterruptedException {
        String topicKey = "resource:test:codec:default";
        ResourceTopic topic = new ResourceTopic(client, topicKey);
        ResourceDescriptor descriptor = new ResourceDescriptor(
                ResourceTypes.APPLICATION, "codec-test-app", List.of(), "public", "public/", false);

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ResourceEvent> received = new AtomicReference<>();
        try (ResourceTopic.Subscription ignored = topic.subscribe(List.of(descriptor), event -> {
            received.set(event);
            latch.countDown();
        })) {
            String json = "{\"url\":\"" + descriptor.getUrl() + "\","
                    + "\"action\":\"CREATE\","
                    + "\"timestamp\":42,"
                    + "\"etag\":\"abc\","
                    + "\"senderPodId\":\"pod-x\"}";
            client.getTopic(topicKey, StringCodec.INSTANCE).publish(json);

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS),
                    "subscriber must receive event despite unknown senderPodId field");
            ResourceEvent event = received.get();
            Assertions.assertNotNull(event);
            Assertions.assertEquals(descriptor.getUrl(), event.getUrl());
            Assertions.assertEquals(ResourceEvent.Action.CREATE, event.getAction());
            Assertions.assertEquals(42L, event.getTimestamp());
            Assertions.assertEquals("abc", event.getEtag());
        }
    }
}
