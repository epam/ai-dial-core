package com.epam.aidial.core.storage.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RScript;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.StringCodec;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LockServiceFailureTest {

    /**
     * A Redis failure thrown from the lock script EVAL must not leak the per-key local
     * ReentrantLock: otherwise every subsequent lock() on the same key blocks forever
     * (until pod restart), which is how a single transient Redis outage wedged the
     * bucket-locked config-rebuild pipeline in production.
     */
    @Test
    void testLocalLockReleasedWhenRedisEvalFails() throws Exception {
        RScript script = mock(RScript.class);
        RedissonClient redis = mock(RedissonClient.class);
        when(redis.getScript(any(StringCodec.class))).thenReturn(script);

        AtomicInteger calls = new AtomicInteger();
        when(script.eval(any(RScript.Mode.class), anyString(), any(RScript.ReturnType.class), anyList(), any(Object[].class)))
                .thenAnswer(invocation -> {
                    if (calls.getAndIncrement() == 0) {
                        throw new RedisException("simulated connection failure");
                    }
                    RScript.ReturnType returnType = invocation.getArgument(2);
                    return returnType == RScript.ReturnType.BOOLEAN ? Boolean.TRUE : 0L;
                });

        LockService service = new LockService(redis, null);

        assertThrows(RedisException.class, () -> service.lock("key"));

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            // Lock must be acquired and closed on the same thread (ReentrantLock semantics).
            Future<Boolean> reacquire = executor.submit(() -> {
                try (LockService.Lock lock = service.lock("key")) {
                    return lock != null;
                }
            });
            assertTrue(reacquire.get(5, TimeUnit.SECONDS));
        } finally {
            executor.shutdownNow();
        }
    }

    /**
     * The lock script returns the remaining ttl in microseconds while the waiter parks in nanoseconds.
     * Reading the ttl as nanoseconds capped every park at a few microseconds, so a waiter on a lock
     * with under 128 ms left spun on Redis until it expired.
     */
    @Test
    void testParkIntervalConvertsTtlFromMicroseconds() {
        long min = TimeUnit.MILLISECONDS.toNanos(1);
        long max = TimeUnit.MILLISECONDS.toNanos(128);

        // doubles while the lock has plenty of ttl left
        assertEquals(2 * min, LockService.nextParkInterval(min, TimeUnit.SECONDS.toMicros(300)));
        // never exceeds the max
        assertEquals(max, LockService.nextParkInterval(max, TimeUnit.SECONDS.toMicros(300)));
        // capped by the remaining ttl: 50 ms left -> park ~50 ms, not ~50 us
        assertEquals(TimeUnit.MICROSECONDS.toNanos(50_001), LockService.nextParkInterval(max, TimeUnit.MILLISECONDS.toMicros(50)));
        // toNanos() saturates instead of overflowing
        assertEquals(max, LockService.nextParkInterval(max, Long.MAX_VALUE / 2));
    }
}
