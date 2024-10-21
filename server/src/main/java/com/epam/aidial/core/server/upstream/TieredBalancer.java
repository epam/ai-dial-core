package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

/**
 * Tiered load balancer. Each next() call returns an available upstream from the highest tier (lowest tier value in config).
 * If the whole tier (highest) is unavailable, balancer start routing upstreams from next tier (lower) if any.
 */
public class TieredBalancer implements LoadBalancer<UpstreamState> {

    @Getter
    private final String deploymentName;
    @Getter
    private final List<Upstream> originalUpstreams;
    private final List<WeightedRoundRobinBalancer> tiers;

    public TieredBalancer(String deploymentName, List<Upstream> upstreams) {
        this.deploymentName = deploymentName;
        this.originalUpstreams = upstreams;
        this.tiers = buildTiers(deploymentName, upstreams);
    }

    @Nullable
    @Override
    public UpstreamState next() {
        for (WeightedRoundRobinBalancer tier : tiers) {
            UpstreamState upstreamState = tier.next();
            if (upstreamState != null) {
                return upstreamState;
            }
        }

        return null;
    }

    private static List<WeightedRoundRobinBalancer> buildTiers(String deploymentName, List<Upstream> upstreams) {
        List<WeightedRoundRobinBalancer> balancers = new ArrayList<>();
        Map<Integer, List<Upstream>> groups = upstreams.stream()
                .collect(Collectors.groupingBy(Upstream::getTier));

        for (Map.Entry<Integer, List<Upstream>> entry : groups.entrySet()) {
            balancers.add(new WeightedRoundRobinBalancer(deploymentName, entry.getValue()));
        }

        balancers.sort(Comparator.naturalOrder());

        return balancers;
    }
}
