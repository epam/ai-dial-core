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
}
