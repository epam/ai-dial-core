package com.epam.aidial.core.service;

import com.epam.aidial.core.storage.BlobStorage;
import com.epam.aidial.core.storage.BlobStorageUtil;
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

    public Lock lock(String key) {
        String id = id(key);
        long owner = ThreadLocalRandom.current().nextLong();
        long ttl = tryLock(id, owner);
        long interval = WAIT_MIN;

        while (ttl > 0) {
            LockSupport.parkNanos(interval);
            interval = Math.min(2 * interval, WAIT_MAX);
            ttl = tryLock(id, owner);
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
        long ttl = tryLock(id, owner);
        return (ttl == 0) ? () -> unlock(id, owner) : null;
    }

    private long tryLock(String id, long owner) {
        return script.eval(RScript.Mode.READ_WRITE,
                """
                        local time = redis.call('time')
                        local now = time[1] * 1000000 + time[2]
                        local deadline = tonumber(redis.call('hget', KEYS[1], 'deadline'))
                                                
                        if (deadline ~= nil and now < deadline) then
                          return deadline - now
                        end
                                                
                        redis.call('hset', KEYS[1], 'owner', ARGV[1], 'deadline', now + ARGV[2])
                        return 0
                        """, RScript.ReturnType.INTEGER, List.of(id), String.valueOf(owner), String.valueOf(PERIOD));
    }

    private void unlock(String id, long owner) {
        boolean ok = tryUnlock(id, owner);
        if (!ok) {
            log.error("Lock service failed to unlock: " + id);
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