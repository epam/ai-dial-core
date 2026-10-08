package com.epam.aidial.core.storage.service;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

class LockServiceTest {

    private static RedisServer server;
    private static RedissonClient client;
    private static LockService service;
    private static SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void init() throws IOException {
        try {
            meterRegistry = new SimpleMeterRegistry();
            Metrics.addRegistry(meterRegistry);

            server = RedisServer.newRedisServer()
                    .port(16371)
                    .bind("127.0.0.1")
                    .setting("maxmemory 4M")
                    .setting("maxmemory-policy volatile-lfu")
                    .build();
            server.start();

            Config config = new Config();
            config.useSingleServer().setAddress("redis://localhost:16371");

            client = Redisson.create(config);
            service = new LockService(client, null);
        } catch (Throwable e) {
            destroy();
            throw e;
        }
    }

    @AfterAll
    static void destroy() throws IOException {
        Metrics.removeRegistry(meterRegistry);
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
    void testLock() {
        for (int i = 0; i < 10; i++) {
            LockService.Lock lock = service.lock("key");
            Assertions.assertNull(service.tryLock("key"));
            lock.close();

            lock = service.tryLock("key");
            Assertions.assertNotNull(lock);
            lock.close();
        }
    }

    @Test
    void testLockRecordsWaitAndHold() {
        long localCount = waitTimer("local").count();
        long redisCount = waitTimer("redis").count();
        long holdCount = holdTimer().count();

        service.lock("metrics-key").close();

        Assertions.assertEquals(localCount + 1, waitTimer("local").count());
        Assertions.assertEquals(redisCount + 1, waitTimer("redis").count());
        Assertions.assertEquals(holdCount + 1, holdTimer().count());
    }

    @Test
    void testLocalContentionIsRecordedAsLocalWait() throws Exception {
        LockService.Lock lock = service.lock("local-contended");
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> service.lock("local-contended").close());
        Thread.sleep(300);
        lock.close();
        waiter.get(5, TimeUnit.SECONDS);

        Assertions.assertTrue(waitTimer("local").max(TimeUnit.MILLISECONDS) >= 250);
        Assertions.assertTrue(holdTimer().max(TimeUnit.MILLISECONDS) >= 250);
    }

    @Test
    void testCrossInstanceContentionIsRecordedAsRedisWait() throws Exception {
        // a second LockService has its own local locks, like another Core pod sharing the Redis
        LockService otherPod = new LockService(client, null);
        LockService.Lock lock = otherPod.lock("redis-contended");
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> service.lock("redis-contended").close());
        Thread.sleep(300);
        lock.close();
        waiter.get(5, TimeUnit.SECONDS);

        Assertions.assertTrue(waitTimer("redis").max(TimeUnit.MILLISECONDS) >= 250);
    }

    private static Timer waitTimer(String phase) {
        return meterRegistry.get("dial_lock_wait").tag("phase", phase).timer();
    }

    private static Timer holdTimer() {
        return meterRegistry.get("dial_lock_hold").timer();
    }
}
