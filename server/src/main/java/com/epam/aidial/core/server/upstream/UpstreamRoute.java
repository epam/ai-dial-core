package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.http.HttpClientResponse;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
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

    private final TieredBalancer balancer;
    /**
     * The maximum number of attempts the route may retry
     */
    private final int maxRetryAttempts;

    /**
     * Current upstream
     */
    @Nullable
    private Upstream upstream;
    /**
     * Attempt counter
     */
    @Getter
    private int attemptCount;

    private final Set<Upstream> usedUpstreams = new HashSet<>();

    public UpstreamRoute(TieredBalancer balancer, int maxRetryAttempts) {
        this.balancer = balancer;
        this.maxRetryAttempts = maxRetryAttempts;
    }

    /**
     * Checks if upstream present (not null) and usage does not exceed max value
     *
     * @return true if upstream available, false otherwise
     */
    public boolean available() {
        return upstream != null && attemptCount <= maxRetryAttempts;
    }

    /**
     *  Retrieves next available upstream from load balancer; also increase usage count
     *
     * @return next upstream from load balancer
     * @throws com.epam.aidial.core.storage.http.HttpException if max retry attempts are exceeded or next upstream is unavailable
     */
    public Upstream next() {
        // if max attempts reached - do not call balancer
        if (attemptCount + 1 > maxRetryAttempts) {
            this.upstream = null;
            throw balancer.createUpstreamUnavailableException();
        }
        attemptCount++;
        upstream = balancer.next(usedUpstreams);
        if (upstream == null) {
            throw balancer.createUpstreamUnavailableException();
        }
        return upstream;
    }

    /**
     * @return get current upstream. null if no upstream available
     */
    @Nullable
    public Upstream get() {
        return upstream;
    }

    public void fail(HttpStatus status) {
        fail(status, -1);
    }

    public void fail(HttpClientResponse response) {
        long retryAfter = retrieveRetryAfterSeconds(response);
        HttpStatus status = HttpStatus.fromStatusCode(response.statusCode());
        fail(status, retryAfter);
    }

    /**
     * Fail current upstream due to error
     *
     * @param status - response http status; typically, 5xx or 429
     * @param retryAfterSeconds - the amount of seconds after which upstream should be available
     */
    void fail(HttpStatus status, long retryAfterSeconds) {
        verifyCurrentUpstream();
        balancer.fail(upstream, status, retryAfterSeconds);
    }

    public void succeed() {
        verifyCurrentUpstream();
        balancer.succeed(upstream);
    }

    private void verifyCurrentUpstream() {
        Objects.requireNonNull(upstream, "current upstream is undefined");
    }

    /**
     * @param response http response from upstream
     * @return the amount of seconds after which upstream should be available or -1 if the value is not provided
     */
    private static long retrieveRetryAfterSeconds(HttpClientResponse response) {
        try {
            String retryAfterHeaderValue = response.getHeader("Retry-After");
            if (retryAfterHeaderValue != null) {
                log.debug("Retry-After header value: {}", retryAfterHeaderValue);
                return Long.parseLong(retryAfterHeaderValue);
            }
            log.debug("Retry-after header not found, status code {}", response.statusCode());
        } catch (Exception e) {
            log.warn("Failed to parse Retry-After header value, fallback to the default value: " + UpstreamState.DEFAULT_RETRY_AFTER_SECONDS_VALUE, e);
        }

        return -1;
    }
}