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
        Thread waiter = startWaiter("local-contended");
        Thread.sleep(300);
        lock.close();
        join(waiter);

        Assertions.assertTrue(waitTimer("local").max(TimeUnit.MILLISECONDS) >= 250);
        Assertions.assertTrue(holdTimer().max(TimeUnit.MILLISECONDS) >= 250);
    }

    @Test
    void testCrossInstanceContentionIsRecordedAsRedisWait() throws Exception {
        // a second LockService has its own local locks, like another Core pod sharing the Redis
        LockService otherPod = new LockService(client, null);
        LockService.Lock lock = otherPod.lock("redis-contended");
        Thread waiter = startWaiter("redis-contended");
        Thread.sleep(300);
        lock.close();
        join(waiter);

        Assertions.assertTrue(waitTimer("redis").max(TimeUnit.MILLISECONDS) >= 250);
    }

    /**
     * Returns once the waiter thread is parked inside lock(): the whole hold that follows counts as its wait,
     * however late the thread is scheduled. Before lock() the thread has nothing to park on.
     */
    private static Thread startWaiter(String key) throws InterruptedException {
        Thread waiter = new Thread(() -> service.lock(key).close());
        waiter.start();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!isParked(waiter)) {
            Assertions.assertTrue(System.nanoTime() < deadline, () -> "the waiter should block in lock(), state=" + waiter.getState());
            Thread.sleep(1);
        }
        return waiter;
    }

    private static boolean isParked(Thread thread) {
        Thread.State state = thread.getState();
        return state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING;
    }

    private static void join(Thread waiter) throws InterruptedException {
        waiter.join(TimeUnit.SECONDS.toMillis(5));
        Assertions.assertFalse(waiter.isAlive(), "the waiter should have taken and released the lock");
    }

    private Timer waitTimer(String phase) {
        return meterRegistry.get("dial_lock_wait").tag("phase", phase).timer();
    }

    private Timer holdTimer() {
        return meterRegistry.get("dial_lock_hold").timer();
    }
}
