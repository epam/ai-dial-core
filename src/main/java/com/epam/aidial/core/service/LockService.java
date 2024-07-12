package com.epam.aidial.core.service;

import com.epam.aidial.core.storage.BlobStorageUtil;
import com.epam.aidial.core.storage.ResourceDescription;
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

    public Lock lock(String key) {
        String id = id(key);
        long owner = ThreadLocalRandom.current().nextLong();

        long interval = WAIT_MIN;
        while (!tryLock(id, owner)) {
            LockSupport.parkNanos(interval);
            interval = Math.min(2 * interval, WAIT_MAX);
        }

        return () -> unlock(id, owner);
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
        return tryLock(id, owner) ? () -> unlock(id, owner) : null;
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

    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }
}