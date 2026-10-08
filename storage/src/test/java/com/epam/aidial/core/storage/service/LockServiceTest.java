package com.epam.aidial.core.storage.service;

import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class LockServiceTest {

    private static RedisServer server;
    private static RedissonClient client;
    private static LockService service;
    // per test: max() of a timer shared by the whole class would carry over the other tests' waits and holds
    private SimpleMeterRegistry meterRegistry;

    @BeforeAll
    static void init() throws IOException {
        try {
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

    @BeforeEach
    void addMeterRegistry() {
        meterRegistry = new SimpleMeterRegistry();
        Metrics.addRegistry(meterRegistry);
    }

    @AfterEach
    void removeMeterRegistry() {
        Metrics.removeRegistry(meterRegistry);
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
        service.lock("metrics-key").close();

        Assertions.assertEquals(1, waitTimer("local").count());
        Assertions.assertEquals(1, waitTimer("redis").count());
        Assertions.assertEquals(1, holdTimer().count());
    }

    @Test
    void testLocalContentionIsRecordedAsLocalWait() throws Exception {
        LockService.Lock lock = service.lock("local-contended");
        CompletableFuture<Void> waiter = startWaiter("local-contended");
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
        CompletableFuture<Void> waiter = startWaiter("redis-contended");
        Thread.sleep(300);
        lock.close();
        waiter.get(5, TimeUnit.SECONDS);

        Assertions.assertTrue(waitTimer("redis").max(TimeUnit.MILLISECONDS) >= 250);
    }

    /**
     * Returns once the waiter thread is about to call lock(), so a slow thread start does not shorten the measured wait.
     */
    private static CompletableFuture<Void> startWaiter(String key) throws InterruptedException {
        CountDownLatch started = new CountDownLatch(1);
        CompletableFuture<Void> waiter = CompletableFuture.runAsync(() -> {
            started.countDown();
            service.lock(key).close();
        });
        Assertions.assertTrue(started.await(5, TimeUnit.SECONDS));
        return waiter;
    }

    private Timer waitTimer(String phase) {
        return meterRegistry.get("dial_lock_wait").tag("phase", phase).timer();
    }

    private Timer holdTimer() {
        return meterRegistry.get("dial_lock_hold").timer();
    }
}
