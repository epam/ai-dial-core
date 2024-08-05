package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.http.HttpClientResponse;

import javax.annotation.Nullable;

/**
 * Not thread-safe! Upstream route with retry count management.<br>
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
public class UpstreamRoute {

    private final LoadBalancer<UpstreamState> balancer;
    /**
     * The maximum number of retries for all upstreams.
     */
    private final int maxRetries;

    /**
     * Current upstream state
     */
    @Nullable
    private UpstreamState upstreamState;
    private int retries;

    public UpstreamRoute(LoadBalancer<UpstreamState> balancer, int maxRetries) {
        this.balancer = balancer;
        this.maxRetries = maxRetries;
        this.upstreamState = balancer.get();
    }

    /**
     * @return the number of upstreams which returned any http response.
     */
    public int used() {
        return retries + 1;
    }

    /**
     * @return the number of retries due to connection errors.
     */
    public int retries() {
        return Math.min(retries, maxRetries);
    }

    /**
     * Checks if upstream present (not null) and retry count does not exceed max retry attempts
     *
     * @return true if upstream available, false otherwise
     */
    public boolean available() {
        return upstreamState != null && retries <= maxRetries;
    }

    /**
     * @return next upstream from load balancer and increase retry counter. null if no upstreams available
     */
    @Nullable
    public Upstream next() {
        retries++;
        UpstreamState upstreamState = balancer.get();
        this.upstreamState = upstreamState;
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
    public void failed(HttpStatus status, long retryAfterSeconds) {
        if (upstreamState == null) {
            return;
        }
        upstreamState.failed(status, retryAfterSeconds);
    }

    public void succeed() {
        if (upstreamState == null) {
            return;
        }
        upstreamState.succeeded();
    }

    /**
     * @param response http response from upstream
     * @return the amount of seconds after which upstream should be available
     */
    public static long calculateRetryAfterSeconds(HttpClientResponse response) {
        long retryAfterSeconds = 30;
        String retryAfterHeaderValue = response.getHeader("Retry-After");
        if (retryAfterHeaderValue != null) {
            retryAfterSeconds = Long.parseLong(retryAfterHeaderValue);
        }

        return retryAfterSeconds;
    }
}