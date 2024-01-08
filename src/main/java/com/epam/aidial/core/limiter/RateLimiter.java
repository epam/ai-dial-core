package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class RateLimiter {
    private final ConcurrentHashMap<String, Entity> traceIdToEntity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id, RateLimit> rates = new ConcurrentHashMap<>();

    public void increase(ProxyContext context) {
        Entity entity = getEntityFromTracingContext(context);
        if (entity == null || entity.isUser()) {
            return;
        }
        Deployment deployment = context.getDeployment();
        TokenUsage usage = context.getTokenUsage();

        if (usage == null || usage.getTotalTokens() <= 0) {
            return;
        }

        entity.setTokeUsage(context.getCurrentSpanId(), usage);

        Id id = new Id(entity.getId(), deployment.getName(), entity.isUser());
        RateLimit rate = rates.computeIfAbsent(id, k -> new RateLimit());

        long timestamp = System.currentTimeMillis();
        rate.add(timestamp, usage.getTotalTokens());
    }

    public RateLimitResult limit(ProxyContext context) {
        Entity entity = getEntityFromTracingContext(context);
        if (entity == null) {
            log.warn("Entity is not found by traceId={}", context.getTraceId());
            return new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied");
        }
        Limit limit;
        if (entity.isUser()) {
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

        Id id = new Id(entity.getId(), deployment.getName(), entity.isUser());
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
        boolean result = entity != null;
        if (result) {
            entity.link(context.getParentSpanId(), context.getCurrentSpanId());
            // update context with the original requester
            if (entity.isUser()) {
                context.setUserHash(entity.getName());
            } else {
                context.setOriginalProject(entity.getName());
            }
        } else {
            if (context.getKey() != null) {
                Key key = context.getKey();
                entity = new Entity(key.getKey(), Collections.singletonList(key.getRole()), key.getProject(), false);
                traceIdToEntity.put(traceId, entity);
            } else {
                entity = new Entity(context.getUserSub(), context.getUserRoles(), context.getUserHash(), true);
                traceIdToEntity.put(traceId, entity);
            }
        }
        entity.addCall(context.getCurrentSpanId());
        return result;
    }

    public void calculateTokenUsage(ProxyContext context) {
        Entity entity = getEntityFromTracingContext(context);
        if (entity == null) {
            log.warn("Entity is not found by traceId={}", context.getTraceId());
            return;
        }
        context.setTokenUsage(entity.calculate(context.getCurrentSpanId()));
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

    @Data
    private static class Entity {

        private final String id;
        private final List<String> roles;
        private final String name;
        private final boolean user;
        private final Map<String, CallInfo> spanIdToCallInfo = new HashMap<>();

        public Entity(String id, List<String> roles, String name, boolean user) {
            this.id = id;
            this.roles = roles;
            this.name = name;
            this.user = user;
        }

        public synchronized void addCall(String spanId) {
            spanIdToCallInfo.put(spanId, new CallInfo());
        }

        public synchronized void link(String parentSpanId, String childSpanId) {
            CallInfo callInfo = spanIdToCallInfo.get(parentSpanId);
            if (callInfo == null) {
                log.warn("Parent span is not found by id: {}", parentSpanId);
                return;
            }
            callInfo.childSpanIds.add(childSpanId);
        }

        public synchronized void setTokeUsage(String spanId, TokenUsage tokenUsage) {
            CallInfo callInfo = spanIdToCallInfo.get(spanId);
            if (callInfo == null) {
                log.warn("Span is not found by id: {}", spanId);
                return;
            }
            callInfo.setTokenUsage(tokenUsage);
        }

        public synchronized TokenUsage calculate(String spanId) {
            CallInfo callInfo = spanIdToCallInfo.get(spanId);
            if (callInfo == null) {
                log.warn("Span is not found by id: {}", spanId);
                return null;
            }
            TokenUsage tokenUsage = callInfo.tokenUsage;
            for (String childSpanId : callInfo.childSpanIds) {
                CallInfo childCall = spanIdToCallInfo.get(childSpanId);
                if (childCall == null) {
                    log.warn("Child span is not found by id: {}", childSpanId);
                    continue;
                }
                tokenUsage.increase(childCall.tokenUsage);
            }
            return tokenUsage;
        }

        @Override
        public String toString() {
            return String.format("Entity: %s, resource: %s, user: %b", id, roles, user);
        }
    }

    @Data
    private static class CallInfo {
        TokenUsage tokenUsage = new TokenUsage();
        List<String> childSpanIds = new ArrayList<>();
    }

}
