package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.util.HttpStatus;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.ReadableSpan;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public abstract class BaseRateLimiter<E> implements RateLimiter {
    private final ConcurrentHashMap<String, E> traceIdToEntity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Id, RateLimit> rates = new ConcurrentHashMap<>();

    public void increase(ProxyContext context) {
        String entityId = getEntityId(context);
        Deployment deployment = context.getDeployment();
        TokenUsage usage = context.getTokenUsage();

        if (usage == null || usage.getTotalTokens() <= 0) {
            return;
        }

        Id id = new Id(entityId, deployment.getName());
        RateLimit rate = rates.computeIfAbsent(id, k -> new RateLimit());

        long timestamp = System.currentTimeMillis();
        rate.add(timestamp, usage.getTotalTokens());
    }

    public RateLimitResult limit(ProxyContext context) {

        Limit limit = getLimit(context);
        Deployment deployment = context.getDeployment();

        if (limit == null || !limit.isPositive()) {
            if (limit == null) {
                log.warn("Limit is not found for deployment: {}", deployment.getName());
            } else {
                log.warn("Limit must be positive for deployment: {}", deployment.getName());
            }
            return new RateLimitResult(HttpStatus.FORBIDDEN, "Access denied");
        }

        Id id = new Id(getEntityId(context), deployment.getName());
        RateLimit rate = rates.get(id);

        if (rate == null) {
            return RateLimitResult.SUCCESS;
        }

        long timestamp = System.currentTimeMillis();
        return rate.update(timestamp, limit);
    }

    public boolean register(ProxyContext context) {
        ReadableSpan span = (ReadableSpan) Span.current();
        String traceId = span.getSpanContext().getTraceId();
        if (span.getParentSpanContext().isRemote()) {
            return traceIdToEntity.containsKey(traceId);
        } else {
            E entity = getEntity(context);
            if (entity != null) {
                traceIdToEntity.put(traceId, entity);
            }
            return true;
        }
    }

    public void unregister(ProxyContext context) {
        ReadableSpan span = (ReadableSpan) Span.current();
        if (!span.getParentSpanContext().isRemote()) {
            String traceId = span.getSpanContext().getTraceId();
            traceIdToEntity.remove(traceId);
        }
    }

    protected abstract E getEntity(ProxyContext context);

    protected abstract String getEntityId(ProxyContext context);

    protected abstract Limit getLimit(ProxyContext context);

    protected E getEntityFromTracingContext() {
        Span span = Span.current();
        String traceId = span.getSpanContext().getTraceId();
        return traceIdToEntity.get(traceId);
    }

    private record Id(String key, String resource) {
        @Override
        public String toString() {
            return String.format("key: %s, resource: %s", key, resource);
        }
    }

}
