package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.http.HttpClientResponse;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

/**
 * Not thread-safe! Upstream route with retry management.<br>
 * This class should be used to route only one user request with retry ability.<br>
 * Typical usage:
 * <pre>
 * if (!available()) {
 *     return "No route"; // if no upstream available or max retry attempts reached - return No route
 * }
 * Upstream upstream = get(); // get current upstream and send request
 * if (error) { // if request failed with 5xx or 429 - report current upstream as failed and call next for retry
 *     failed();
 *     next(); // retry
 * } else {
 *     succeed(); // if 200 - report upstream as succeeded
 * }
 * </pre>
 */
@Slf4j
public class UpstreamRoute {

    private static final long DEFAULT_RETRY_AFTER_SECONDS_VALUE = 30;

    private final LoadBalancer<UpstreamState> balancer;
    /**
     * The maximum number of upstreams this route can use due to retries.
     */
    private final int maxUpstreamsToUse;

    /**
     * Current upstream state
     */
    @Nullable
    private UpstreamState upstreamState;
    private int used;

    public UpstreamRoute(LoadBalancer<UpstreamState> balancer, int maxUpstreamsToUse) {
        this.balancer = balancer;
        this.maxUpstreamsToUse = maxUpstreamsToUse;
        this.upstreamState = balancer.next();
        this.used = upstreamState == null ? 0 : 1;
    }

    /**
     * @return the number of used upstreams.
     */
    public int used() {
        return used;
    }

    /**
     * Checks if upstream present (not null) and usage does not exceed max value
     *
     * @return true if upstream available, false otherwise
     */
    public boolean available() {
        return upstreamState != null && used <= maxUpstreamsToUse;
    }

    /**
     *  Retrieves next available upstream from load balancer; also increase usage count
     *
     * @return next upstream from load balancer
     */
    @Nullable
    public Upstream next() {
        // if max attempts reached - do not call balancer
        if (used + 1 > maxUpstreamsToUse) {
            this.upstreamState = null;
            return null;
        }
        used++;
        this.upstreamState = balancer.next();
        return upstreamState == null ? null : upstreamState.getUpstream();
    }

    /**
     * @return get current upstream. null if no upstream available
     */
    @Nullable
    public Upstream get() {
        return upstreamState == null ? null : upstreamState.getUpstream();
    }

    /**
     * Fail current upstream due to error
     *
     * @param status - response http status; typically, 5xx or 429
     * @param retryAfterSeconds - the amount of seconds after which upstream should be available; if status 5xx this value ignored
     */
    public void fail(HttpStatus status, long retryAfterSeconds) {
        if (upstreamState != null) {
            upstreamState.failed(status, retryAfterSeconds);
        }
    }

    public void fail(HttpStatus status) {
        fail(status, DEFAULT_RETRY_AFTER_SECONDS_VALUE);
    }

    public void fail(HttpClientResponse response) {
        fail(HttpStatus.fromStatusCode(response.statusCode()), calculateRetryAfterSeconds(response));
    }

    public void succeed() {
        if (upstreamState != null) {
            upstreamState.succeeded();
        }
    }

    /**
     * @param response http response from upstream
     * @return the amount of seconds after which upstream should be available
     */
    private static long calculateRetryAfterSeconds(HttpClientResponse response) {
        try {
            String retryAfterHeaderValue = response.getHeader("Retry-After");
            log.info("Retry-After header value: {}", retryAfterHeaderValue);
            if (retryAfterHeaderValue != null) {
                return Long.parseLong(retryAfterHeaderValue);
            }
            log.info("Retry-after header not found, available headers: {}", response.headers());
        } catch (Exception e) {
            log.warn("Failed to parse Retry-After header value, fallback to the default value: " + DEFAULT_RETRY_AFTER_SECONDS_VALUE, e);
        }

        return DEFAULT_RETRY_AFTER_SECONDS_VALUE;
    }
}