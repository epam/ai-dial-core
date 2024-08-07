package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import javax.annotation.Nullable;

@Getter
@Slf4j
public class WeightedRoundRobinBalancer implements Comparable<WeightedRoundRobinBalancer>, LoadBalancer<UpstreamState> {

    private final int tier;
    private final List<UpstreamState> upstreams;
    private final String deploymentName;

    private int currentIndex = -1;
    private int currentWeight;

    public WeightedRoundRobinBalancer(String deploymentName, List<Upstream> upstreams) {
        this.deploymentName = deploymentName;
        if (upstreams == null || upstreams.isEmpty()) {
            throw new IllegalArgumentException("Upstream list is null or empty");
        }
        int tier = upstreams.get(0).getTier();
        for (Upstream upstream : upstreams) {
            if (upstream.getTier() != tier) {
                throw new IllegalArgumentException("Tier mismatch");
            }
        }
        this.tier = tier;
        this.upstreams = upstreams.stream()
                .filter(upstream -> upstream.getWeight() > 0)
                .map(upstream -> new UpstreamState(upstream, Upstream.ERROR_THRESHOLD))
                .sorted(Comparator.reverseOrder())
                .toList();
        if (this.upstreams.isEmpty()) {
            log.warn("No available upstreams for deployment %s and tier %d".formatted(deploymentName, tier));
        }
    }

    @Nullable
    @Override
    public synchronized UpstreamState next() {
        List<UpstreamState> upstreams = getAvailableUpstreams();
        if (upstreams.isEmpty()) {
            return null;
        }
        int maxWeight = getMaxAvailableWeight(upstreams);
        int gcdWeight = getGreatestCommonDivisor(upstreams);
        int upstreamsCount = upstreams.size();
        while (true) {
            currentIndex = (currentIndex + 1) % upstreamsCount;
            if (currentIndex == 0) {
                currentWeight -= gcdWeight;
                if (currentWeight <= 0) {
                    currentWeight = maxWeight;
                    if (currentWeight == 0) {
                        return null;
                    }
                }
            }
            if (upstreams.get(currentIndex).getUpstream().getWeight() >= currentWeight) {
                return upstreams.get(currentIndex);
            } else {
                currentIndex = -1;
            }
        }
    }

    @Override
    public int compareTo(WeightedRoundRobinBalancer weightedRoundRobinBalancer) {
        return Integer.compare(tier, weightedRoundRobinBalancer.tier);
    }

    private List<UpstreamState> getAvailableUpstreams() {
        List<UpstreamState> availableUpstreams = new ArrayList<>();
        for (UpstreamState upstream : upstreams) {
            // skip upstream if not available
            if (!upstream.isUpstreamAvailable()) {
                continue;
            }
            availableUpstreams.add(upstream);
        }
        return availableUpstreams;
    }

    private static int getMaxAvailableWeight(List<UpstreamState> upstreams) {
        return upstreams.stream()
                .map(UpstreamState::getUpstream)
                .map(Upstream::getWeight)
                .max(Integer::compareTo)
                .orElse(0);
    }

    private static int getGreatestCommonDivisor(List<UpstreamState> upstreams) {
        int[] weights = upstreams.stream()
                .map(UpstreamState::getUpstream)
                .mapToInt(Upstream::getWeight)
                .toArray();
        return Arrays.stream(weights).reduce(WeightedRoundRobinBalancer::gcd).orElse(-1);
    }

    private static int gcd(int a, int b) {
        if (b == 0) {
            return a;
        }
        return gcd(b, a % b);
    }
}
