package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Upstream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Load balancer distributes load in the proportion of probability of upstream weights.
 * The higher upstream weight, the higher probability the upstream takes more load.
 */
@Slf4j
class RandomizedWeightedBalancer implements Comparable<RandomizedWeightedBalancer> {

    private final int tier;
    @Getter
    private final List<UpstreamState> upstreamStates;
    private final Random generator;

    RandomizedWeightedBalancer(String deploymentName, List<Upstream> upstreams, Random generator) {
        if (upstreams == null || upstreams.isEmpty()) {
            throw new IllegalArgumentException("Upstream list is null or empty for deployment: " + deploymentName);
        }
        int tier = upstreams.get(0).getTier();
        for (Upstream upstream : upstreams) {
            if (upstream.getTier() != tier) {
                throw new IllegalArgumentException("Tier mismatch for deployment " + deploymentName);
            }
        }
        this.tier = tier;
        this.upstreamStates = upstreams.stream()
                .filter(upstream -> upstream.getWeight() > 0)
                .map(UpstreamState::new)
                .toList();
        this.generator = generator;
        if (this.upstreamStates.isEmpty()) {
            log.warn("No available upstreams for deployment {} and tier {}", deploymentName, tier);
        }
    }

    public Upstream next() {
        if (upstreamStates.isEmpty()) {
            return null;
        }

        List<Upstream> availableUpstreams = upstreamStates.stream().filter(UpstreamState::isUpstreamAvailable)
                .map(UpstreamState::getUpstream).toList();
        if (availableUpstreams.isEmpty()) {
            return null;
        }
        int total = availableUpstreams.stream().map(Upstream::getWeight).reduce(0, Integer::sum);
        // make sure the upper bound `total` is inclusive
        int random = generator.nextInt(total + 1);
        int current = 0;

        Upstream result = null;

        for (Upstream upstream : availableUpstreams) {
            current += upstream.getWeight();
            if (current >= random) {
                result = upstream;
                break;
            }
        }

        return result;
    }

    @Override
    public int compareTo(RandomizedWeightedBalancer randomizedWeightedBalancer) {
        return Integer.compare(tier, randomizedWeightedBalancer.tier);
    }

}
