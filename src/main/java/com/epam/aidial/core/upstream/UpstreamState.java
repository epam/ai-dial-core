package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.util.HttpStatus;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

@Slf4j
public class UpstreamState implements Comparable<UpstreamState> {

    @Getter
    private final Upstream upstream;
    private final int errorsThreshold;

    private static final long INITIAL_BACKOFF_DELAY_MS = 1000;
    // max backoff delay - 5 minutes
    private static final long MAX_BACKOFF_DELAY_MS = 5 * 60 * 1000;

    /**
     * Amount of 5xx errors from upstream
     */
    private int errorCount;
    /**
     * Timestamp in millis when upstream may be available
     */
    private long retryAfter = -1;

    public UpstreamState(Upstream upstream, int errorsThreshold) {
        this.upstream = upstream;
        this.errorsThreshold = errorsThreshold;
    }

    /**
     * Register upstream failure. Supported error codes are 429 and 5xx.
     *
     * @param status response status code from upstream
     * @param retryAfterSeconds time in seconds when upstream may become available; only take into account with 429 status code
     */
    public synchronized void failed(HttpStatus status, long retryAfterSeconds) {
        if (status == HttpStatus.TOO_MANY_REQUESTS) {
            retryAfter = System.currentTimeMillis() + Math.max(retryAfterSeconds, 0) * 1000;
            log.debug("Upstream limit hit: retry after: {} millis, {}", retryAfter, Instant.ofEpochMilli(retryAfter).toString());
        }

        if (status.is5xx()) {
            if (++errorCount >= errorsThreshold) {
                retryAfter = System.currentTimeMillis()
                             + Math.min(INITIAL_BACKOFF_DELAY_MS * (1L << errorCount), MAX_BACKOFF_DELAY_MS);
            }
        }
    }

    /**
     * reset errors state
     */
    public synchronized void succeeded() {
        // reset errors
        errorCount = 0;
        retryAfter = -1;
    }

    public synchronized boolean isUpstreamAvailable() {
        if (retryAfter < 0) {
            return true;
        }

        return System.currentTimeMillis() > retryAfter;
    }

    @Override
    public int compareTo(UpstreamState upstreamState) {
        return Integer.compare(upstream.getWeight(), upstreamState.getUpstream().getWeight());
    }
}
