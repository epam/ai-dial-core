package com.epam.aidial.core.mcp.ratelimit;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * Per-session token-bucket limiter with a concurrency cap. In-memory only; no external libs.
 */
@Slf4j
public class McpSessionLimiter {

    // Fixed-point scale to avoid floating-point token fractions inside the CAS loop.
    private static final long SCALE = 1000L;
    private static final long NANOS_PER_MINUTE = 60L * 1_000_000_000L;
    private static final int MAX_CAS_RETRIES = 4;

    private final int callsPerMinute;
    private final int burstCapacity;
    private final int maxConcurrentPerSession;
    private final LongSupplier nanoTimeSource;
    private final long maxElapsedNanos;
    private final ConcurrentHashMap<String, SessionState> sessions = new ConcurrentHashMap<>();

    public McpSessionLimiter(int callsPerMinute, int burstCapacity, int maxConcurrentPerSession,
                             LongSupplier nanoTimeSource) {
        this.callsPerMinute = callsPerMinute;
        this.burstCapacity = burstCapacity;
        this.maxConcurrentPerSession = maxConcurrentPerSession;
        this.nanoTimeSource = nanoTimeSource;
        this.maxElapsedNanos = NANOS_PER_MINUTE * burstCapacity / callsPerMinute;
    }

    public McpSessionLimiter(int callsPerMinute, int burstCapacity, int maxConcurrentPerSession) {
        this(callsPerMinute, burstCapacity, maxConcurrentPerSession, System::nanoTime);
    }

    public Decision tryAcquire(String sessionId) {
        SessionState state = sessions.computeIfAbsent(sessionId,
                k -> new SessionState(nanoTimeSource.getAsLong(), (long) burstCapacity * SCALE));

        if (state.concurrency.get() >= maxConcurrentPerSession) {
            return new Decision.Deny(1L);
        }

        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            long last = state.lastRefillNanos.get();
            long currentScaled = state.tokensScaled.get();
            long now = nanoTimeSource.getAsLong();
            long elapsed = Math.min(maxElapsedNanos, Math.max(0L, now - last));
            long refillScaled = elapsed * callsPerMinute * SCALE / NANOS_PER_MINUTE;
            long capScaled = (long) burstCapacity * SCALE;
            long beforeConsume = Math.min(capScaled, currentScaled + refillScaled);
            long newScaled = beforeConsume - SCALE;

            if (newScaled < 0L) {
                double tokensPerSec = (double) callsPerMinute * SCALE / 60.0;
                long retryAfter = Math.max(1L, (long) Math.ceil(-newScaled / tokensPerSec));
                return new Decision.Deny(retryAfter);
            }

            if (state.tokensScaled.compareAndSet(currentScaled, newScaled)) {
                state.lastRefillNanos.compareAndSet(last, now);
                state.concurrency.incrementAndGet();
                return new Decision.Allow();
            }
        }

        log.debug("CAS contention on session {}, falling back to Deny(1)", sessionId);
        return new Decision.Deny(1L);
    }

    public void release(String sessionId) {
        SessionState s = sessions.get(sessionId);
        if (s != null) {
            s.concurrency.updateAndGet(c -> Math.max(0, c - 1));
        }
    }

    public void evict(String sessionId) {
        sessions.remove(sessionId);
    }

    private static final class SessionState {
        final AtomicLong tokensScaled;
        final AtomicLong lastRefillNanos;
        final AtomicInteger concurrency = new AtomicInteger(0);

        SessionState(long nowNanos, long initialTokensScaled) {
            this.tokensScaled = new AtomicLong(initialTokensScaled);
            this.lastRefillNanos = new AtomicLong(nowNanos);
        }
    }
}
