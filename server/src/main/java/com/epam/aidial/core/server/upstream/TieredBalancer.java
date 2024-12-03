package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.http.HttpHeaders;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Tiered load balancer. Each next() call returns an available upstream from the highest tier (lowest tier value in config).
 * If the whole tier (highest) is unavailable, balancer start routing upstreams from next tier (lower) if any.
 */
class TieredBalancer {

    private final List<RandomizedWeightedBalancer> tiers;

    private final List<UpstreamState> upstreamStates = new ArrayList<>();

    private final List<Predicate<UpstreamState>> predicates = new ArrayList<>();

    public TieredBalancer(String deploymentName, List<Upstream> upstreams, Random random) {
        this.tiers = buildTiers(deploymentName, upstreams, random);
        for (RandomizedWeightedBalancer tier : tiers) {
            upstreamStates.addAll(tier.getUpstreamStates());
        }
        predicates.add(state -> state.getStatus().is5xx()
                && state.getSource() == UpstreamState.RetryAfterSource.CORE);
        predicates.add(state -> state.getStatus().is5xx()
                && state.getSource() == UpstreamState.RetryAfterSource.UPSTREAM);
        predicates.add(state -> state.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                && state.getSource() == UpstreamState.RetryAfterSource.CORE);
        predicates.add(state -> state.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                && state.getSource() == UpstreamState.RetryAfterSource.UPSTREAM);
    }

    @Nullable
    synchronized Upstream next(Set<Upstream> usedUpstreams) {
        for (RandomizedWeightedBalancer tier : tiers) {
            Upstream upstream = tier.next();
            if (upstream != null) {
                return upstream;
            }
        }
        // fallback
        for (Predicate<UpstreamState> p : predicates) {
            UpstreamState candidate = upstreamStates.stream().filter(p)
                    .filter(upstreamState -> !usedUpstreams.contains(upstreamState.getUpstream()))
                    .min(Comparator.comparingLong(UpstreamState::getRetryAfter)).orElse(null);
            if (candidate != null) {
                usedUpstreams.add(candidate.getUpstream());
                return candidate.getUpstream();
            }
        }
        return null;
    }

    synchronized void fail(Upstream upstream, HttpStatus status, long retryAfterSeconds) {
        Objects.requireNonNull(upstream);
        UpstreamState upstreamState = findUpstreamState(upstream);
        upstreamState.fail(status, retryAfterSeconds);
    }

    synchronized HttpException createUpstreamUnavailableException() {
        int busyUpstreamsCount = 0;
        for (UpstreamState upstreamState : upstreamStates) {
            if (upstreamState.getStatus() == HttpStatus.TOO_MANY_REQUESTS) {
                busyUpstreamsCount++;
            }
        }
        if (busyUpstreamsCount == upstreamStates.size()) {
            long replyAfter = -1;
            for (UpstreamState upstreamState : upstreamStates) {
                if (upstreamState.getStatus() == HttpStatus.TOO_MANY_REQUESTS
                        && upstreamState.getSource() == UpstreamState.RetryAfterSource.UPSTREAM) {
                    if (replyAfter == -1) {
                        replyAfter = upstreamState.getRetryAfter();
                    } else {
                        replyAfter = Math.min(replyAfter, upstreamState.getRetryAfter());
                    }
                }
            }
            String errorMessage = "Service is not available";
            if (replyAfter == -1) {
                // no upstreams with the requested source
                // we don't provide reply-after header
                return new HttpException(HttpStatus.SERVICE_UNAVAILABLE, errorMessage);
            } else {
                // according to the spec: A non-negative decimal integer indicating the seconds to delay after the response is received.
                long replyAfterInSeconds = Math.max(0, TimeUnit.MILLISECONDS.toSeconds(replyAfter - System.currentTimeMillis()));
                return new HttpException(HttpStatus.SERVICE_UNAVAILABLE, errorMessage,
                        Map.of(HttpHeaders.RETRY_AFTER.toString(), Long.toString(replyAfterInSeconds)));
            }
        }
        // default error - no route
        return new HttpException(HttpStatus.BAD_GATEWAY, "No route");
    }

    synchronized void succeed(Upstream upstream) {
        Objects.requireNonNull(upstream);
        UpstreamState upstreamState = findUpstreamState(upstream);
        upstreamState.succeeded();
    }

    private UpstreamState findUpstreamState(Upstream upstream) {
        for (UpstreamState upstreamState : upstreamStates) {
            if (upstreamState.getUpstream().equals(upstream)) {
                return upstreamState;
            }
        }
        throw new IllegalArgumentException("Upstream is not found: " + upstream);
    }

    private static List<RandomizedWeightedBalancer> buildTiers(String deploymentName, List<Upstream> upstreams, Random random) {
        List<RandomizedWeightedBalancer> balancers = new ArrayList<>();
        Map<Integer, List<Upstream>> groups = upstreams.stream()
                .collect(Collectors.groupingBy(Upstream::getTier));

        for (Map.Entry<Integer, List<Upstream>> entry : groups.entrySet()) {
            balancers.add(new RandomizedWeightedBalancer(deploymentName, entry.getValue(), random));
        }

        balancers.sort(Comparator.naturalOrder());

        return balancers;
    }
}
