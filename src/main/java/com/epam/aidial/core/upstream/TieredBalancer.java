package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import lombok.Getter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;
import javax.annotation.Nullable;

public class TieredBalancer implements LoadBalancer<UpstreamState> {

    @Getter
    private final String deploymentName;
    private List<Upstream> originalUpstreams;
    private List<WeightedRoundRobinBalancer> tiers;

    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public TieredBalancer(String deploymentName, List<Upstream> upstreams) {
        this.deploymentName = deploymentName;
        this.originalUpstreams = upstreams;
        this.tiers = buildTiers(deploymentName, upstreams);
    }

    @Nullable
    @Override
    public UpstreamState get() {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            for (WeightedRoundRobinBalancer tier : tiers) {
                UpstreamState upstreamState = tier.get();
                if (upstreamState != null) {
                    return upstreamState;
                }
            }

            return null;
        } finally {
            readLock.unlock();
        }
    }

    public void updateUpstreams(List<Upstream> upstreams) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            // if config doesn't change - return
            if (new HashSet<>(upstreams).equals(new HashSet<>(originalUpstreams))) {
                return;
            }

            // rebuild balancer
            originalUpstreams = upstreams;
            tiers = buildTiers(this.deploymentName, upstreams);

        } finally {
            writeLock.unlock();
        }
    }

    private static List<WeightedRoundRobinBalancer> buildTiers(String deploymentName, List<Upstream> upstreams) {
        List<WeightedRoundRobinBalancer> balancers = new ArrayList<>();
        Map<Integer, List<Upstream>> groups = upstreams.stream()
                .collect(Collectors.groupingBy(Upstream::getTier));

        for (Map.Entry<Integer, List<Upstream>> entry : groups.entrySet()) {
            balancers.add(new WeightedRoundRobinBalancer(deploymentName, entry.getValue()));
        }

        balancers.sort(Comparator.reverseOrder());

        return balancers;
    }
}
