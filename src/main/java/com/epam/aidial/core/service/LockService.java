package com.epam.aidial.core.service;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import javax.annotation.Nullable;

/**
 * Simple spin-lock implementation which works with Redis as cache. Supports volatile-* eviction policies.
 */
@Slf4j
public class LockService {

    private static final long PERIOD = TimeUnit.SECONDS.toMicros(300);
    private static final long WAIT_MIN = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long WAIT_MAX = TimeUnit.MILLISECONDS.toNanos(128);

    private final String prefix;
    private final RScript script;

    public LockService(RedissonClient redis, @Nullable String prefix) {
        this.prefix = prefix;
        this.script = redis.getScript(StringCodec.INSTANCE);
    }

    public String redisKey(ResourceDescription descriptor) {
        String resourcePath = BlobStorageUtil.toStoragePath(prefix, descriptor.getAbsoluteFilePath());
        return descriptor.getType().name().toLowerCase() + ":" + resourcePath;
    }

    public Lock lock(ResourceDescription resource) {
        return lock(redisKey(resource));
    }

    public Lock lock(String key) {
        String id = id(key);
        long owner = ThreadLocalRandom.current().nextLong();

        long interval = WAIT_MIN;
        while (!tryLock(id, owner)) {
            LockSupport.parkNanos(interval);
            interval = Math.min(2 * interval, WAIT_MAX);
        }

        return new Lock(id, owner);
    }

    public MoveLock lock(ResourceDescription resource1, ResourceDescription resource2) {
        return lock(redisKey(resource1), redisKey(resource2));
    }

    private MoveLock lock(String key1, String key2) {
        String id1 = id(key1);
        String id2 = id(key2);
        long owner = ThreadLocalRandom.current().nextLong();

        long interval = WAIT_MIN;
        while (true) {
            Lock lock1 = tryLock(id1);
            if (lock1 != null) {
                Lock lock2 = tryLock(id2);
                if (lock2 != null) {
                    return new MoveLock(id1, id2, owner);
                }
                lock1.close();
            }

            LockSupport.parkNanos(interval);
            interval = Math.min(2 * interval, WAIT_MAX);
        }
    }

    public <T> T underBucketLock(String bucketLocation, Supplier<T> function) {
        String key = BlobStorageUtil.toStoragePath(prefix, bucketLocation);
        try (var ignored = lock(key)) {
            return function.get();
        }
    }

    @Nullable
    public Lock tryLock(String key) {
        String id = id(key);
        long owner = ThreadLocalRandom.current().nextLong();
        return tryLock(id, owner) ? new Lock(id, owner) : null;
    }

    private boolean tryLock(String id, long owner) {
        return script.eval(RScript.Mode.READ_WRITE,
                """
                        local time = redis.call('time')
                        local now = time[1] * 1000000 + time[2]
                        local deadline = tonumber(redis.call('hget', KEYS[1], 'deadline'))
                        
                        if (deadline ~= nil and now < deadline) then
                          return false
                        end
                        
                        redis.call('hset', KEYS[1], 'owner', ARGV[1], 'deadline', now + ARGV[2])
                        return true
                        """, RScript.ReturnType.BOOLEAN, List.of(id), String.valueOf(owner), String.valueOf(PERIOD));
    }

    private boolean tryExtend(String id, long owner) {
        return script.eval(RScript.Mode.READ_WRITE,
                """
                        local time = redis.call('time')
                        local now = time[1] * 1000000 + time[2]
                        local data = redis.call('hmget', KEYS[1], 'owner', 'deadline')
                        local deadline = tonumber(redis.call('hget', KEYS[1], 'deadline'))
                        
                        if (data[1] == ARGV[1] and deadline ~= nil and now < deadline) then
                          redis.call('hset', KEYS[1], 'deadline', now + ARGV[2])
                          return true
                        end
                        
                        return false
                        """, RScript.ReturnType.BOOLEAN, List.of(id), String.valueOf(owner), String.valueOf(PERIOD));
    }

    private void unlock(String id, long owner) {
        boolean ok = tryUnlock(id, owner);
        if (!ok) {
            log.error("Lock service failed to unlock: {}", id);
        }
    }

    private boolean tryUnlock(String id, long owner) {
        return script.eval(RScript.Mode.READ_WRITE,
                """
                        local owner = redis.call('hget', KEYS[1], 'owner')
                        
                        if (owner == ARGV[1]) then
                          redis.call('del', KEYS[1])
                          return true
                        end
                        
                        return false
                        """, RScript.ReturnType.BOOLEAN, List.of(id), String.valueOf(owner));
    }

    private static String id(String key) {
        return "lock:" + key;
    }

    @AllArgsConstructor
    public class MoveLock implements AutoCloseable {
        private final String id1;
        private final String id2;
        private final long owner;

        @Override
        public void close() {
            unlock(id1, owner);
            unlock(id2, owner);
        }
    }

    @AllArgsConstructor
    public final class Lock implements AutoCloseable {
        private final String id;
        private final long owner;

        public void extend() {
            if (!LockService.this.tryExtend(id, owner)) {
                throw new IllegalStateException("Failed to acquire storage lock");
            }
        }

        @Override
        public void close() {
            unlock(id, owner);
        }
    }
}