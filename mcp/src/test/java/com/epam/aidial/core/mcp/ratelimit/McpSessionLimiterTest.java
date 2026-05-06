package com.epam.aidial.core.mcp.ratelimit;

import org.junit.jupiter.api.Test;

import java.util.function.LongSupplier;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class McpSessionLimiterTest {

    private static final long NS_PER_SEC = 1_000_000_000L;

    private static class Clock implements LongSupplier {
        long now;

        @Override
        public long getAsLong() {
            return now;
        }

        void advanceSeconds(long seconds) {
            now += seconds * NS_PER_SEC;
        }
    }

    @Test
    void burstAbsorption() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 3, 10, clock);

        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));

        Decision fourth = limiter.tryAcquire("s1");
        assertInstanceOf(Decision.Deny.class, fourth);
        assertTrue(((Decision.Deny) fourth).retryAfterSeconds() >= 1L);
    }

    @Test
    void steadyStateRefill() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 3, 10, clock);

        for (int i = 0; i < 3; i++) {
            assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        }
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));

        clock.advanceSeconds(1);
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));

        clock.advanceSeconds(1);
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
    }

    @Test
    void refillCappedAtBurst() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 3, 10, clock);

        for (int i = 0; i < 3; i++) {
            assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        }
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));

        clock.advanceSeconds(1000);
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));
    }

    @Test
    void independentSessionBudgets() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 2, 10, clock);

        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));

        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s2"));
    }

    @Test
    void concurrencyCapAcquireRelease() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(600, 100, 2, clock);

        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));

        Decision third = limiter.tryAcquire("s1");
        assertInstanceOf(Decision.Deny.class, third);
        assertEquals(1L, ((Decision.Deny) third).retryAfterSeconds());

        limiter.release("s1");
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
    }

    @Test
    void concurrencyDenyDoesNotConsumeToken() {
        Clock clock = new Clock();
        // burst = 2, maxConcurrent = 1: hit concurrency cap before tokens.
        McpSessionLimiter limiter = new McpSessionLimiter(60, 2, 1, clock);

        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));

        limiter.release("s1");
        // If concurrency-deny consumed tokens, only one Allow would remain after release;
        // but we should still have 1 token left after the first Allow (burst=2).
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
    }

    @Test
    void evictClearsState() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 2, 2, clock);

        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        // s1 is now at concurrency=2 and tokens=0.

        limiter.evict("s1");

        // Fresh state: full burst available, concurrency reset.
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
    }

    @Test
    void overReleaseClampsAtZero() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 5, 2, clock);

        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertDoesNotThrow(() -> {
            limiter.release("s1");
            limiter.release("s1");
            limiter.release("s1");
        });

        // After over-release, should still allow up to maxConcurrent acquires.
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        Decision third = limiter.tryAcquire("s1");
        assertInstanceOf(Decision.Deny.class, third);
        assertEquals(1L, ((Decision.Deny) third).retryAfterSeconds());
    }

    @Test
    void evictUnknownSessionIsNoOp() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 5, 2, clock);
        assertDoesNotThrow(() -> limiter.evict("never-seen"));
    }

    @Test
    void releaseOnUnknownSessionIsNoOp() {
        Clock clock = new Clock();
        McpSessionLimiter limiter = new McpSessionLimiter(60, 5, 2, clock);
        assertDoesNotThrow(() -> limiter.release("never-seen"));
    }

    @Test
    void longIdleSessionRefillsToBurstWithoutOverflow() {
        // Use elevated rate that would overflow sooner: callsPerMinute=600, SCALE=1000 → threshold ~4 hours.
        // Advance well past that (100 hours) to exercise the clamp path.
        long[] now = {0L};
        McpSessionLimiter limiter = new McpSessionLimiter(600, 5, 10, () -> now[0]);
        // Drain burst at t=0
        for (int i = 0; i < 5; i++) {
            assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        }
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));
        // Advance 100 hours (3.6e14 ns) — past the overflow threshold.
        now[0] = 100L * 3_600L * 1_000_000_000L;
        // Burst is fully refilled — 5 allows, then deny
        for (int i = 0; i < 5; i++) {
            assertInstanceOf(Decision.Allow.class, limiter.tryAcquire("s1"));
        }
        assertInstanceOf(Decision.Deny.class, limiter.tryAcquire("s1"));
    }
}
