package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.storage.http.HttpStatus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;
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
