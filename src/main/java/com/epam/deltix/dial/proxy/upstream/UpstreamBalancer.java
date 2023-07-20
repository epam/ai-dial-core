package com.epam.deltix.dial.proxy.upstream;

import com.epam.deltix.dial.proxy.config.Upstream;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class UpstreamBalancer {

    private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>(); // no eviction yet

    public UpstreamRoute balance(UpstreamProvider provider) {
        String name = provider.getName();
        List<Upstream> upstreams = provider.getUpstreams();

        if (upstreams.size() <= 1) {
            return new UpstreamRoute(upstreams, null, 0);
        }

        AtomicLong counter = counters.computeIfAbsent(name, (key) -> new AtomicLong());
        int offset = (int) (counter.getAndIncrement() % upstreams.size());
        return new UpstreamRoute(upstreams, counter, offset);
    }
}
