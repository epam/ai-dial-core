package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import io.opentelemetry.api.trace.Span;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RateLimiter {
    private final ConcurrentHashMap<String, Entity> traceIdToEntity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id, RateLimit> rates = new ConcurrentHashMap<>();

    public void increase(ProxyContext context) {
        Entity entity = getEntityFromTracingContext(context);
        if (entity == null || entity.user()) {
            return;
        }
        Deployment deployment = context.getDeployment();
        TokenUsage usage = context.getTokenUsage();

        if (usage == null || usage.getTotalTokens() <= 0) {
            return;
        }

        Id id = new Id(entity.id(), deployment.getName(), entity.user());
        RateLimit rate = rates.computeIfAbsent(id, k -> new RateLimit());

        long timestamp = System.currentTimeMillis();
        rate.add(timestamp, usage.getTotalTokens());
    }

    public RateLimitResult limit(ProxyContext context) {
        Entity entity = getEntityFromTracingContext(context);
        if (entity == null) {
            Span span = Span.current();
            log.warn("Entity is not found by traceId={}", span.getSpanContext().getTraceId());
            return new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied");
        }
        Limit limit;
        if (entity.user()) {
            // don't support user limits yet
            return RateLimitResult.SUCCESS;
        } else {
            limit = getLimitByApiKey(context, entity);
        }

        Deployment deployment = context.getDeployment();

        if (limit == null || !limit.isPositive()) {
            if (limit == null) {
                log.warn("Limit is not found for deployment: {}", deployment.getName());
            } else {
                log.warn("Limit must be positive for deployment: {}", deployment.getName());
            }
            return new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied");
        }

        Id id = new Id(entity.id(), deployment.getName(), entity.user());
        RateLimit rate = rates.get(id);

        if (rate == null) {
            return RateLimitResult.SUCCESS;
        }

        long timestamp = System.currentTimeMillis();
        return rate.update(timestamp, limit);
    }

    /**
     * Returns <code>true</code> if the trace is already registered otherwise <code>false</code>.
     */
    public boolean register(ProxyContext context) {
        String traceId = context.getTraceId();
        Entity entity = traceIdToEntity.get(traceId);
        if (entity != null) {
            // update context with the original requester
            if (entity.user()) {
                context.setUserHash(entity.name());
            } else {
                context.setOriginalProject(entity.name());
            }
        } else {
            if (context.getKey() != null) {
                Key key = context.getKey();
                traceIdToEntity.put(traceId, new Entity(key.getKey(), Collections.singletonList(key.getRole()), key.getProject(), false));
            } else {
                traceIdToEntity.put(traceId, new Entity(context.getUserSub(), context.getUserRoles(), context.getUserHash(), true));
            }
        }
        return entity != null;
    }

    public void unregister(ProxyContext context) {
        String traceId = context.getTraceId();
        traceIdToEntity.remove(traceId);
    }

    private Limit getLimitByApiKey(ProxyContext context, Entity entity) {
        // API key has always one role
        Role role = context.getConfig().getRoles().get(entity.roles.get(0));

        if (role == null) {
            log.warn("Role is not found for key: {}", context.getKey().getKey());
            return null;
        }

        Deployment deployment = context.getDeployment();
        return role.getLimits().get(deployment.getName());
    }

    private Entity getEntityFromTracingContext(ProxyContext context) {
        String traceId = context.getTraceId();
        return traceIdToEntity.get(traceId);
    }

    private record Id(String key, String resource, boolean user) {
        @Override
        public String toString() {
            return String.format("key: %s, resource: %s, user: %b", key, resource, user);
        }
    }

    private record Entity(String id, List<String> roles, String name, boolean user) {
        @Override
        public String toString() {
            return String.format("Entity: %s, resource: %s, user: %b", id, roles, user);
        }
    }

}
