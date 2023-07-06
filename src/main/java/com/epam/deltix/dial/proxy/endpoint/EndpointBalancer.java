package com.epam.deltix.dial.proxy.endpoint;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicLong;

public class EndpointBalancer {

    private final ConcurrentMap<String, AtomicLong> counters = new ConcurrentHashMap<>(); // no eviction yet

    public EndpointRoute balance(EndpointProvider provider) {
        String name = provider.getName();
        String[] endpoints = provider.getEndpoints().keySet().toArray(String[]::new);

        if (endpoints.length <= 1) {
            return new EndpointRoute(endpoints, null, 0);
        }

        AtomicLong counter = counters.computeIfAbsent(name, (key) -> new AtomicLong());
        int offset = (int) (counter.getAndIncrement() % endpoints.length);
        return new EndpointRoute(endpoints, counter, offset);
    }
}
