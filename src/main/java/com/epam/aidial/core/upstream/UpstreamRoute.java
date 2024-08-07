package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.util.HttpStatus;
import io.vertx.core.http.HttpClientResponse;
import lombok.extern.slf4j.Slf4j;

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
@Slf4j
public class UpstreamRoute {

    private static final long DEFAULT_RETRY_AFTER_SECONDS_VALUE = 30;

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
        this.upstreamState = balancer.next();
    }

    /**
     * @return the number of upstreams which returned any http response.
     */
    public int used() {
        return retries + 1;
    }

    /**
     * Checks if upstream present (not null) and retry count does not exceed max retry attempts
     *
     * @return true if upstream available, false otherwise
     */
    public boolean available() {
        return upstreamState != null && retries < maxRetries;
    }

    /**
     * @return next upstream from load balancer and increase retry counter. null if no upstreams available
     */
    @Nullable
    public Upstream next() {
        retries++;
        UpstreamState upstreamState = balancer.next();
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
    public void fail(HttpStatus status, long retryAfterSeconds) {
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
        try {
            String retryAfterHeaderValue = response.getHeader("Retry-After");
            if (retryAfterHeaderValue != null) {
                return Long.parseLong(retryAfterHeaderValue);
            }
        } catch (Exception e) {
            log.warn("Error parsing retry-after header, fallback to the default value: " + DEFAULT_RETRY_AFTER_SECONDS_VALUE, e);
        }

        return DEFAULT_RETRY_AFTER_SECONDS_VALUE;
    }
}