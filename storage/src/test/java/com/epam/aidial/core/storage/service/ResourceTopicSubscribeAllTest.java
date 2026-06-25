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
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class ResourceTopicSubscribeAllTest {

    private static RedisServer server;
    private static RedissonClient client;

    @BeforeAll
    static void init() throws IOException {
        try {
            server = RedisServer.newRedisServer()
                    .port(16375)
                    .bind("127.0.0.1")
                    .setting("maxmemory 4M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16375");
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
    void globalSubscriberReceivesEventsForAnyUrl() throws InterruptedException {
        ResourceTopic topic = new ResourceTopic(client, "resource:test:subscribe-all:any-url");

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ResourceEvent> received = new AtomicReference<>();
        try (ResourceTopic.Subscription ignored = topic.subscribeAll(event -> {
            received.set(event);
            latch.countDown();
        })) {
            ResourceEvent event = new ResourceEvent()
                    .setUrl("models/platform/never-pre-registered")
                    .setAction(ResourceEvent.Action.CREATE)
                    .setTimestamp(1L)
                    .setEtag("e1");
            topic.publish(event);

            Assertions.assertTrue(latch.await(5, TimeUnit.SECONDS));
            Assertions.assertEquals("models/platform/never-pre-registered", received.get().getUrl());
        }
    }

    @Test
    void perUrlAndGlobalSubscribersBothFire() throws InterruptedException {
        ResourceTopic topic = new ResourceTopic(client, "resource:test:subscribe-all:both-fire");
        ResourceDescriptor descriptor = new ResourceDescriptor(
                ResourceTypes.APPLICATION, "both-app", List.of(), "public", "public/", false);

        CountDownLatch perUrlLatch = new CountDownLatch(1);
        CountDownLatch globalLatch = new CountDownLatch(1);
        try (ResourceTopic.Subscription perUrl = topic.subscribe(List.of(descriptor), event -> perUrlLatch.countDown());
             ResourceTopic.Subscription global = topic.subscribeAll(event -> globalLatch.countDown())) {
            ResourceEvent event = new ResourceEvent()
                    .setUrl(descriptor.getUrl())
                    .setAction(ResourceEvent.Action.UPDATE)
                    .setTimestamp(2L)
                    .setEtag("e2");
            topic.publish(event);

            Assertions.assertTrue(perUrlLatch.await(5, TimeUnit.SECONDS), "per-URL subscriber must receive");
            Assertions.assertTrue(globalLatch.await(5, TimeUnit.SECONDS), "global subscriber must receive");
        }
    }

    @Test
    void closingSubscriptionStopsDelivery() throws InterruptedException {
        ResourceTopic topic = new ResourceTopic(client, "resource:test:subscribe-all:close");

        AtomicInteger count = new AtomicInteger();
        ResourceTopic.Subscription subscription = topic.subscribeAll(event -> count.incrementAndGet());

        ResourceEvent first = new ResourceEvent()
                .setUrl("schemas/platform/x").setAction(ResourceEvent.Action.CREATE).setTimestamp(1L);
        topic.publish(first);
        waitForCount(count, 1);

        subscription.close();

        ResourceEvent second = new ResourceEvent()
                .setUrl("schemas/platform/x").setAction(ResourceEvent.Action.UPDATE).setTimestamp(2L);
        topic.publish(second);
        Thread.sleep(200);

        Assertions.assertEquals(1, count.get(), "no more events after close()");
    }

    @Test
    void exceptionInGlobalSubscriberDoesNotBreakOthers() throws InterruptedException {
        ResourceTopic topic = new ResourceTopic(client, "resource:test:subscribe-all:exception");

        CountDownLatch survivorLatch = new CountDownLatch(1);
        try (ResourceTopic.Subscription failing = topic.subscribeAll(event -> {
            throw new RuntimeException("boom");
        });
             ResourceTopic.Subscription survivor = topic.subscribeAll(event -> survivorLatch.countDown())) {
            ResourceEvent event = new ResourceEvent()
                    .setUrl("any/url").setAction(ResourceEvent.Action.CREATE).setTimestamp(1L);
            topic.publish(event);

            Assertions.assertTrue(survivorLatch.await(5, TimeUnit.SECONDS),
                    "second global subscriber must still fire when first throws");
        }
    }

    private static void waitForCount(AtomicInteger counter, int target) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (counter.get() < target && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        Assertions.assertEquals(target, counter.get());
    }
}
