package com.epam.aidial.core.resource;

import com.epam.aidial.core.service.LockService;
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
                    .setting("bind 127.0.0.1")
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