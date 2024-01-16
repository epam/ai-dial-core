package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.google.common.annotations.VisibleForTesting;
import io.vertx.core.Future;
import io.vertx.core.Promise;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class TokenStatsTracker {
    private final Map<String, TraceContext> traceIdToContext = new ConcurrentHashMap<>();

    public void startSpan(ProxyContext context) {
        TraceContext traceContext = traceIdToContext.computeIfAbsent(context.getTraceId(), k -> new TraceContext());
        traceContext.addSpan(context);
    }

    public Future<TokenUsage> getTokenStats(ProxyContext context) {
        TraceContext traceContext = traceIdToContext.get(context.getTraceId());
        if (traceContext == null) {
            return Future.succeededFuture();
        }
        return traceContext.getStats(context);
    }

    public void endSpan(ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        if (apiKeyData.getPerRequestKey() == null) {
            traceIdToContext.remove(context.getTraceId());
        } else {
            TraceContext traceContext = traceIdToContext.get(context.getTraceId());
            if (traceContext != null) {
                traceContext.endSpan(context);
            }
        }
    }

    @VisibleForTesting
    Map<String, TraceContext> getTraces() {
        return traceIdToContext;
    }

    private static class TraceContext {
        Map<String, TokenStats> spans = new HashMap<>();

        synchronized void addSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            String parentSpanId = context.getParentSpanId();
            TokenStats tokenStats = new TokenStats(new TokenUsage(), Promise.promise(), new ArrayList<>());
            spans.put(spanId, tokenStats);
            if (parentSpanId != null) {
                TokenStats parent = spans.get(parentSpanId);
                if (parent != null) {
                    parent.children.add(tokenStats.promise.future());
                }
            }
        }

        synchronized void endSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            TokenStats tokenStats = spans.get(spanId);
            if (tokenStats != null) {
                tokenStats.promise.complete(context.getTokenUsage());
            }
        }

        synchronized Future<TokenUsage> getStats(ProxyContext context) {
            TokenStats tokenStats = spans.get(context.getSpanId());
            if (tokenStats == null) {
                return Future.succeededFuture();
            }
            TokenUsage tokenUsage = tokenStats.tokenUsage;
            return Future.all(tokenStats.children).map(result -> {
                for (var child : result.list()) {
                    TokenUsage stats = (TokenUsage) child;
                    tokenUsage.increase(stats);
                }
                return tokenUsage;
            });
        }
    }

    private record TokenStats(TokenUsage tokenUsage, Promise<TokenUsage> promise, List<Future<TokenUsage>> children) {
    }
}
