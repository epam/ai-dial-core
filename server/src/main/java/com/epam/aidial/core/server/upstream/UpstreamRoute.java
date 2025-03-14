package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.data.cache.CachePolicy;
import com.epam.aidial.core.server.data.cache.CachedUpstreamEntry;
import com.epam.aidial.core.server.service.UpstreamCacheService;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Vertx;
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
    private Upstream upstream;
    /**
     * Attempt counter
     */
    @Getter
    private int attemptCount;

    private final Set<Upstream> usedUpstreams = new HashSet<>();

    private final UpstreamCacheContext upstreamCacheContext;

    private final UpstreamCacheService upstreamCacheService;

    private final Vertx vertx;

    public UpstreamRoute(Vertx vertx, UpstreamCacheService upstreamCacheService, TieredBalancer balancer, int maxRetryAttempts, UpstreamCacheContext upstreamCacheContext) {
        this.balancer = balancer;
        this.maxRetryAttempts = maxRetryAttempts;
        this.upstreamCacheContext = upstreamCacheContext;
        this.vertx = vertx;
        this.upstreamCacheService = upstreamCacheService;
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
        if (upstreamCacheContext == null
                || (upstreamCacheContext.getPolicy() != CachePolicy.CACHE_PRIORITY && attemptCount > 1)
                || upstreamCacheContext.getOriginalUpstream() == null) {
            upstream = balancer.next(usedUpstreams);
            if (upstream == null) {
                throw balancer.createUpstreamUnavailableException();
            }
        } else {
            upstream = upstreamCacheContext.getOriginalUpstream();
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
        succeed(null, null);
    }

    public void succeed(HttpClientResponse proxyResponse, Deployment deployment) {
        verifyCurrentUpstream();
        if (deployment instanceof Model model) {
            updateUpstreamCache(proxyResponse, model);
        }
        balancer.succeed(upstream);
    }

    private void updateUpstreamCache(HttpClientResponse proxyResponse, Model model) {
        String breakpointPath = proxyResponse.getHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH);
        if (breakpointPath == null) {
            // no cache
            return;
        }
        String hash = upstreamCacheContext.getPrefixToHash().get(breakpointPath);
        if (hash == null) {
            if (model.getType() != ModelType.EMBEDDING) {
                log.warn("prefix is not found: {}", breakpointPath);
            }
            return;
        }
        String expireAt = proxyResponse.getHeader(Proxy.HEADER_CACHE_EXPIRE_AT);
        CachedUpstreamEntry entry = new CachedUpstreamEntry(upstream.getEndpoint(), breakpointPath);
        vertx.executeBlocking(() -> {
            upstreamCacheService.updateEntry(hash, entry, model, expireAt);
            return null;
        }, false).onFailure(error -> log.error("Error occurred while updating cached upstream entry", error));
    }

    public String getBreakpointPath() {
        if (upstreamCacheContext == null) {
            return null;
        }
        CachedUpstreamEntry entry  = upstreamCacheContext.getEntry();
        return entry == null ? null : entry.prefixPath();
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