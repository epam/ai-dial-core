package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
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

    public RateLimitResult limit(ProxyContext context) {
        Key key = context.getKey();
        Role role = context.getConfig().getRoles().get(key.getRole());

        if (role == null) {
            log.warn("Role is not found for key: {}", context.getKey().getKey());
            return new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied");
        }

        Deployment deployment = context.getDeployment();
        Limit limit = role.getLimits().get(deployment.getName());

        if (limit == null || !limit.isPositive()) {
            if (limit == null) {
                log.warn("Limit is not found for deployment: {}", deployment.getName());
            } else {
                log.warn("Limit must be positive for deployment: {}", deployment.getName());
            }
            return new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied");
        }

        Id id = new Id(key.getKey(), deployment.getName());
        RateLimit rate = rates.get(id);

        if (rate == null) {
            return RateLimitResult.SUCCESS;
        }

        long timestamp = System.currentTimeMillis();
        return rate.update(timestamp, limit);
    }

    private record Id(String key, String resource) {
        @Override
        public String toString() {
            return String.format("key: %s, resource: %s", key, resource);
        }
    }
}