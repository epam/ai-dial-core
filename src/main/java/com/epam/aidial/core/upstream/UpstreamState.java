package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.util.HttpStatus;
import lombok.Getter;

public class UpstreamState implements Comparable<UpstreamState> {

    @Getter
    private final Upstream upstream;
    private final int errorsThreshold;

    private static final long INITIAL_BACKOFF_DELAY_MS = 1000;
    private static final int BACKOFF_BASE = 2;

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
            retryAfter = System.currentTimeMillis() + retryAfterSeconds * 1000;
        }

        if (status.is5xx()) {
            if (++errorCount >= errorsThreshold) {
                retryAfter = System.currentTimeMillis() + (long) (INITIAL_BACKOFF_DELAY_MS * Math.pow(BACKOFF_BASE, errorCount));
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
