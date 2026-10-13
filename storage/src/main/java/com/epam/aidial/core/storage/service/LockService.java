package com.epam.aidial.core.storage.service;

import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.util.LatencyBuckets;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
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
    // lock() only: tryLock() never waits, and its holds (e.g. an application deployment) are not measured either
    private static final Timer LOCAL_WAIT_TIMER = buildWaitTimer("local");
    private static final Timer REDIS_WAIT_TIMER = buildWaitTimer("redis");
    private static final Timer HOLD_TIMER = Timer.builder("dial_lock_hold")
            .description("Time a lock taken with lock() is held")
            .serviceLevelObjectives(LatencyBuckets.WAIT_BUCKETS)
            .register(Metrics.globalRegistry);

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
        long waitStart = System.nanoTime();
        localLock.lock();
        long acquired;

        // A Redis failure here must not leak the held local lock: it is a plain ReentrantLock,
        // so a leaked hold would block every subsequent lock() on this key until pod restart.
        try {
            long localAcquired = System.nanoTime();
            LOCAL_WAIT_TIMER.record(localAcquired - waitStart, TimeUnit.NANOSECONDS);
            long ttl = tryLock(id, owner);
            long interval = WAIT_MIN;
            // it seems the lock has been acquired by another instance of Core
            while (ttl > 0) {
                LockSupport.parkNanos(interval);
                interval = nextParkInterval(interval, ttl);
                ttl = tryLock(id, owner);
            }
            acquired = System.nanoTime();
            REDIS_WAIT_TIMER.record(acquired - localAcquired, TimeUnit.NANOSECONDS);
        } catch (Throwable e) {
            localLock.unlock();
            releaseLocalLock(id);
            throw e;
        }

        return () -> {
            try {
                unlock(id, owner, localLock);
            } finally {
                // the lock was held whether or not the release succeeds
                HOLD_TIMER.record(System.nanoTime() - acquired, TimeUnit.NANOSECONDS);
            }
        };
    }

    private static Timer buildWaitTimer(String phase) {
        // local: other threads of this pod hold the key;
        // redis: the Redis round-trip, plus the spin with backoff while another pod holds the key
        return Timer.builder("dial_lock_wait")
                .description("Time lock() waits before the lock is acquired")
                .tag("phase", phase)
                .serviceLevelObjectives(LatencyBuckets.WAIT_BUCKETS)
                .register(Metrics.globalRegistry);
    }

    static long nextParkInterval(long intervalNanos, long ttlMicros) {
        // toNanos() saturates, so the + 1 goes inside
        return Math.min(2 * intervalNanos, Math.min(WAIT_MAX, TimeUnit.MICROSECONDS.toNanos(ttlMicros + 1)));
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

    private static String id(String key) {
        return "lock:" + key;
    }

    public interface Lock extends AutoCloseable {
        @Override
        void close();
    }
}
