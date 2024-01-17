package com.epam.aidial.core.upstream;

import com.epam.aidial.core.config.Upstream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class UpstreamBalancer {

    private static final int CONNECTION_ERROR_RETRIES = 2;

    private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>(); // no eviction yet

    public UpstreamRoute balance(UpstreamProvider provider) {
        String name = provider.getName();
        List<Upstream> upstreams = provider.getUpstreams();
        int offset = 0;

        if (upstreams.size() > 1) {
            AtomicLong counter = counters.computeIfAbsent(name, (key) -> new AtomicLong());
            offset = (int) (counter.getAndIncrement() % upstreams.size());
        }

        return new UpstreamRoute(upstreams, offset, CONNECTION_ERROR_RETRIES);
    }
}
