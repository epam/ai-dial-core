package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RFuture;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;
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

    @Getter
    private final String prefix;
    private final RScript script;
    private final ConcurrentHashMap<String, LocalLock> locks;

    private static class LocalLock {
        // number of threads requested this lock
        int threadCount = 1;

        ReentrantLock lock = new ReentrantLock();

        void lock() {
            lock.lock();
        }

        void unlock() {
            lock.unlock();
        }
    }

    public LockService(RedissonClient redis, @Nullable String prefix) {
        this.prefix = prefix;
        this.script = redis.getScript(StringCodec.INSTANCE);
        this.locks = new ConcurrentHashMap<>();
    }

    public Lock lock(String key) {
        String id = id(key);
        long owner = ThreadLocalRandom.current().nextLong();
        log.debug("Thread {} acquires a lock to the resource {} with owner {}", Thread.currentThread().getName(), id, owner);
        // try to get a local lock the first
        LocalLock localLock = acquireLocalLock(id);
        localLock.lock();

        // A Redis failure here must not leak the held local lock: it is a plain ReentrantLock,
        // so a leaked hold would block every subsequent lock() on this key until pod restart.
        try {
            long ttl = tryLock(id, owner);
            long interval = WAIT_MIN;
            // it seems the lock has been acquired by another instance of Core
            while (ttl > 0) {
                LockSupport.parkNanos(interval);
                interval = Math.min(2 * interval, Math.min(WAIT_MAX, ttl + 1));
                ttl = tryLock(id, owner);
            }
        } catch (Throwable e) {
            localLock.unlock();
            releaseLocalLock(id);
            throw e;
        }

        return () -> unlock(id, owner, localLock);
    }

    private LocalLock acquireLocalLock(String id) {
        return locks.compute(id, (k, cur) -> {
            if (cur == null) {
                return new LocalLock();
            } else {
                cur.threadCount++;
            }
            return cur;
        });
    }

    public <T> T underBucketLock(String bucketLocation, Supplier<T> function) {
        String key = BlobStorageUtil.toStoragePath(prefix, bucketLocation);
        try (var ignored = lock(key)) {
            return function.get();
        }
    }

    public <T> T underBucketLocks(Collection<String> bucketLocations, Supplier<T> function) {
        List<String> keys = bucketLocations.stream()
                .map(bucketLocation -> BlobStorageUtil.toStoragePath(prefix, bucketLocation))
                .distinct()
                .sorted()
                .toList();

        List<Lock> locks = new ArrayList<>(keys.size());

        try {
            for (String key : keys) {
                Lock lock = lock(key);
                locks.add(lock);
            }

            return function.get();
        } finally {
            for (Lock lock : locks) {
                lock.close();
            }
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

    private void unlock(String id, long owner, LocalLock localLock) {
        try {
            unlock(id, owner);
        } finally {
            localLock.unlock();
            releaseLocalLock(id);
        }
    }

    private void unlock(String id, long owner) {
        boolean ok = tryUnlock(id, owner);
        if (!ok) {
            log.warn("Lock service failed to unlock: {}", id);
        } else {
            log.debug("Thread {} releases a lock to the resource {} with owner {}", Thread.currentThread().getName(), id, owner);
        }
    }

    private void releaseLocalLock(String id) {
        locks.compute(id, (k, cur) -> {
            if (cur == null || --cur.threadCount == 0) {
                return null;
            }
            return cur;
        });
    }

    private boolean tryUnlock(String id, long owner) {
        try {
            return script.eval(RScript.Mode.READ_WRITE,
                    """
                            local owner = redis.call('hget', KEYS[1], 'owner')

                            if (owner == ARGV[1]) then
                              redis.call('del', KEYS[1])
                              return true
                            end

                            return false
                            """, RScript.ReturnType.BOOLEAN, List.of(id), String.valueOf(owner));
        } catch (Throwable e) {
            log.error("Lock service failed to unlock: {}", id, e);
            return false;
        }
    }

    public RFuture<Long> tryCreateClaimAsync(String key, String lockId, long ttlMs) {
        return script.evalAsync(RScript.Mode.READ_WRITE,
                """
                        local ttl = redis.call('pttl', KEYS[1])
                        if ttl >= 0 then
                            return ttl > 0 and ttl or 1
                        end
                        redis.call('set', KEYS[1], ARGV[1], 'px', ARGV[2])
                        return 0
                        """, RScript.ReturnType.INTEGER, List.of(id(key)), lockId, String.valueOf(ttlMs));
    }

    public RFuture<Boolean> tryUpdateClaimAsync(String key, String lockId, long ttlMs) {
        return script.evalAsync(RScript.Mode.READ_WRITE,
                """
                        if redis.call('get', KEYS[1]) == ARGV[1] then
                            redis.call('pexpire', KEYS[1], ARGV[2])
                            return true
                        end
                        return false
                        """, RScript.ReturnType.BOOLEAN, List.of(id(key)), lockId, String.valueOf(ttlMs));
    }

    public RFuture<Boolean> releaseClaimAsync(String key, String lockId) {
        return script.evalAsync(RScript.Mode.READ_WRITE,
                """
                        if redis.call('get', KEYS[1]) == ARGV[1] then
                            redis.call('del', KEYS[1])
                            return true
                        end
                        return false
                        """, RScript.ReturnType.BOOLEAN, List.of(id(key)), lockId);
    }

    private static String id(String key) {
        return "lock:" + key;
    }

    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }
}
