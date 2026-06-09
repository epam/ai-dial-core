package com.epam.aidial.core.storage.service;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import redis.embedded.RedisServer;

import java.io.IOException;

class LockServiceTest {

    private static RedisServer server;
    private static RedissonClient client;
    private static LockService service;

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

    @Test
    void testTryCreateClaimAsync() throws Exception {
        String key = "test-create-claim-key";
        String owner1 = "lock-id-a";
        String owner2 = "lock-id-b";
        long ttl = 5_000L;

        long r1 = service.tryCreateClaimAsync(key, owner1, ttl).get();
        Assertions.assertEquals(0L, r1, "first create should return 0");

        long r2 = service.tryCreateClaimAsync(key, owner1, ttl).get();
        Assertions.assertTrue(r2 > 0, "second create by same id should return positive TTL (key exists)");

        long r3 = service.tryCreateClaimAsync(key, owner2, ttl).get();
        Assertions.assertTrue(r3 > 0, "create by different id should return positive TTL (held by another)");
    }

    @Test
    void testTryUpdateClaimAsync() throws Exception {
        String key = "test-update-claim-key";
        String owner1 = "lock-id-x";
        String owner2 = "lock-id-y";
        long ttl = 5_000L;

        service.tryCreateClaimAsync(key, owner1, ttl).get();

        boolean updated = service.tryUpdateClaimAsync(key, owner1, ttl).get();
        Assertions.assertTrue(updated, "update by matching owner should return true");

        boolean rejected = service.tryUpdateClaimAsync(key, owner2, ttl).get();
        Assertions.assertFalse(rejected, "update by non-owner should return false");
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
}