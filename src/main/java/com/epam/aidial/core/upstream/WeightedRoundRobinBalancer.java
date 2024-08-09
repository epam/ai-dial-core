package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import lombok.extern.slf4j.Slf4j;

import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Implementation of weighted round-robin load balancer.
 * Load balancer tracks upstream statistics and guaranty spreading the load according to the upstreams weight
 */
@Slf4j
public class WeightedRoundRobinBalancer implements Comparable<WeightedRoundRobinBalancer>, LoadBalancer<UpstreamState> {

    private final int tier;
    private final List<UpstreamState> upstreams;
    private final long[] upstreamsWeights;
    private final long[] upstreamsUsage;
    private final long totalWeight;
    private long totalUsage;
    private final PriorityQueue<UpstreamUsage> upstreamPriority = new PriorityQueue<>((a, b) -> Double.compare(b.delta, a.delta));

    public WeightedRoundRobinBalancer(String deploymentName, List<Upstream> upstreams) {
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
        this.upstreams = upstreams.stream()
                .filter(upstream -> upstream.getWeight() > 0)
                .map(upstream -> new UpstreamState(upstream, Upstream.ERROR_THRESHOLD))
                .sorted(Comparator.reverseOrder())
                .toList();
        this.totalWeight = this.upstreams.stream().map(UpstreamState::getUpstream).mapToLong(Upstream::getWeight).sum();
        this.upstreamsUsage = new long[this.upstreams.size()];
        this.upstreamsWeights = this.upstreams.stream().map(UpstreamState::getUpstream).mapToLong(Upstream::getWeight).toArray();
        if (this.upstreams.isEmpty()) {
            log.warn("No available upstreams for deployment %s and tier %d".formatted(deploymentName, tier));
        }
    }

    @Override
    public synchronized UpstreamState next() {
        if (upstreams.isEmpty()) {
            return null;
        }
        try {
            int size = upstreams.size();
            for (int i = 0; i < size; i++) {
                UpstreamState upstreamState = upstreams.get(i);
                double actualUsageRate = upstreamsUsage[i] == 0 ? 0 : (double) upstreamsUsage[i] / totalUsage;
                double expectedUsageRate = (double) upstreamsWeights[i] / totalWeight;
                double delta = expectedUsageRate - actualUsageRate;
                upstreamPriority.offer(new UpstreamUsage(upstreamState, i, delta));
            }
            while (!upstreamPriority.isEmpty()) {
                UpstreamUsage candidate = upstreamPriority.poll();
                totalUsage += 1;
                upstreamsUsage[candidate.upstreamIndex] += 1;
                if (candidate.upstream.isUpstreamAvailable()) {
                    return candidate.upstream;
                }
            }
            return null;
        } finally {
            upstreamPriority.clear();
        }
    }

    @Override
    public int compareTo(WeightedRoundRobinBalancer weightedRoundRobinBalancer) {
        return Integer.compare(tier, weightedRoundRobinBalancer.tier);
    }

    private record UpstreamUsage(UpstreamState upstream, int upstreamIndex, double delta) {
    }
}
