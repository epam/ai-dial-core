package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class LoadBalancerProvider {

    private final Cache<String, TieredBalancer> cache = CacheBuilder.newBuilder()
            .expireAfterAccess(5, TimeUnit.MINUTES)
            .build();

    /**
     * Returns cached UpstreamRoute for the given provider; updates load balancer if provided upstreams do not match original one
     *
     * @param provider upstream provider for any deployment with actual upstreams
     * @return upstream route
     */
    public synchronized UpstreamRoute get(UpstreamProvider provider) {
        String deploymentName = provider.getName();
        List<Upstream> upstreams = provider.getUpstreams();
        TieredBalancer balancer = cache.getIfPresent(deploymentName);
        if (balancer == null) {
            balancer = new TieredBalancer(deploymentName, upstreams);
            cache.put(deploymentName, balancer);
        } else {
            // update upstreams (rebuild balancer if needed) in case of upstreams changed
            balancer.updateUpstreams(upstreams);
        }

        return new UpstreamRoute(balancer, 5);
    }
}
