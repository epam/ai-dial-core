package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import io.opentelemetry.api.trace.Span;

import java.util.concurrent.ConcurrentHashMap;

public abstract class BaseRateLimiter<E> implements RateLimiter {
    private final ConcurrentHashMap<String, E> traceIdToEntity = new ConcurrentHashMap<>();

    public boolean register(ProxyContext context) {
        Span parentSpan = context.getParentSpan();
        if (parentSpan == null) {
            E entity = getEntity(context);
            if (entity != null) {
                String traceId = context.getCurrentSpan().getSpanContext().getTraceId();
                traceIdToEntity.put(traceId, entity);
            }
            return true;
        } else {
            String traceId = context.getParentSpan().getSpanContext().getTraceId();
            return traceIdToEntity.containsKey(traceId);
        }
    }

    public void unregister(ProxyContext context) {
        Span parentSpan = context.getParentSpan();
        if (parentSpan == null) {
            String traceId = context.getCurrentSpan().getSpanContext().getTraceId();
            traceIdToEntity.remove(traceId);
        }
    }

    protected abstract E getEntity(ProxyContext context);

    protected E getEntityFromTracingContext(ProxyContext context) {
        Span parentSpan = context.getParentSpan();
        String traceId;
        if (parentSpan != null) {
            traceId = parentSpan.getSpanContext().getTraceId();
        } else {
            traceId = context.getCurrentSpan().getSpanContext().getTraceId();
        }
        return traceIdToEntity.get(traceId);
    }
}
