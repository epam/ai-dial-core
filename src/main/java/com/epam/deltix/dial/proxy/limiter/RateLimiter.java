package com.epam.deltix.dial.proxy.limiter;

import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.Deployment;
import com.epam.deltix.dial.proxy.config.Key;
import com.epam.deltix.dial.proxy.config.Limit;
import com.epam.deltix.dial.proxy.config.Role;
import com.epam.deltix.dial.proxy.token.TokenUsage;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;

@RequiredArgsConstructor
public class RateLimiter {

    private final ConcurrentHashMap<Id, RateLimit> rates = new ConcurrentHashMap<>();

    public void increase(ProxyContext context) {
        Key key = context.getKey();
        Deployment deployment = context.getDeployment();
        TokenUsage usage = context.getTokenUsage();

        if (usage == null || usage.getTotalTokens() <= 0) {
            return;
        }

        Id id = new Id(key.getKey(), deployment.getName());
        RateLimit rate = rates.computeIfAbsent(id, k -> new RateLimit());

        long timestamp = System.currentTimeMillis();
        rate.add(timestamp, usage.getTotalTokens());
    }

    public boolean limit(ProxyContext context) {
        Key key = context.getKey();
        Role role = context.getConfig().getRoles().get(key.getRole());

        if (role == null) {
            return true;
        }

        Deployment deployment = context.getDeployment();
        Limit limit = role.getLimits().get(deployment.getName());

        if (limit == null || !limit.isPositive()) {
            return true;
        }

        Id id = new Id(key.getKey(), deployment.getName());
        RateLimit rate = rates.get(id);

        if (rate == null) {
            return false;
        }

        long timestamp = System.currentTimeMillis();
        return rate.update(timestamp, limit);
    }

    private record Id(String key, String resource) {
    }
}