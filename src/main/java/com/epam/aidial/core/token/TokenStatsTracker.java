package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;

@Slf4j
@RequiredArgsConstructor
public class TokenStatsTracker {
    public static final String DEPLOYMENT_COST_STATS_BUCKET = "deployment_cost_stats";
    public static final String DEPLOYMENT_COST_STATS_LOCATION = DEPLOYMENT_COST_STATS_BUCKET + PATH_SEPARATOR;

    private final Vertx vertx;
    private final ResourceService resourceService;

    /**
     * Starts current span.
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     */
    public Future<Void> startSpan(ProxyContext context) {
        return vertx.executeBlocking(() -> {
            ResourceDescription resource = toResource(context.getTraceId());
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    traceContext = new TraceContext();
                }
                traceContext.addSpan(context);
                return ProxyUtil.convertToString(traceContext);
            });
            return null;
        }, false);
    }

    public Future<TokenUsage> getTokenStats(ProxyContext context) {
        return vertx.executeBlocking(() -> {
            ResourceDescription resource = toResource(context.getTraceId());
            String json = resourceService.getResource(resource);
            TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
            if (traceContext == null) {
                return null;
            }
            return traceContext.getStats(context);
        }, false);
    }

    /**
     * Ends current span.
     */
    public Future<Void> endSpan(ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        if (apiKeyData.getPerRequestKey() == null) {
            return vertx.executeBlocking(() -> {
                ResourceDescription resource = toResource(context.getTraceId());
                resourceService.deleteResource(resource, EtagHeader.ANY);
                return null;
            }, false);
        } else {
            // we don't need to remove the span from trace context right now.
            // we can do it later when the initial span is completed
            return Future.succeededFuture();
        }
    }

    public Future<TokenUsage> updateModelStats(ProxyContext context) {
        ResourceDescription resource = toResource(context.getTraceId());
        return vertx.executeBlocking(() -> {
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    return null;
                }
                traceContext.updateStats(context.getSpanId(), context.getTokenUsage());
                return ProxyUtil.convertToString(traceContext);
            });
            return context.getTokenUsage();
        }, false);
    }

    @Data
    public static class TraceContext {
        Map<String, TokenStats> spans = new HashMap<>();

        void addSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            String parentSpanId = context.getParentSpanId();
            TokenStats tokenStats = new TokenStats(new TokenUsage(), parentSpanId);
            spans.put(spanId, tokenStats);
        }

        TokenUsage getStats(ProxyContext context) {
            TokenStats tokenStats = spans.get(context.getSpanId());
            if (tokenStats == null) {
                return null;
            }
            return tokenStats.tokenUsage;
        }

        void updateStats(String spanId, TokenUsage tokenUsage) {
            TokenStats tokenStats = spans.get(spanId);
            if (tokenStats == null) {
                return;
            }
            tokenStats.tokenUsage = tokenUsage;
            String parenSpanId = tokenStats.parentSpanId;
            while (parenSpanId != null) {
                tokenStats = spans.get(parenSpanId);
                tokenStats.tokenUsage.increase(tokenUsage);
                parenSpanId = tokenStats.parentSpanId;
            }
        }
    }

    @Data
    public static class TokenStats {
        TokenUsage tokenUsage;
        String parentSpanId;

        public TokenStats() {
        }

        public TokenStats(TokenUsage tokenUsage, String parentSpanId) {
            this.tokenUsage = tokenUsage;
            this.parentSpanId = parentSpanId;
        }
    }

    private static ResourceDescription toResource(String traceId) {
        return ResourceDescription.fromDecoded(
                ResourceType.DEPLOYMENT_COST_STATS, DEPLOYMENT_COST_STATS_BUCKET, DEPLOYMENT_COST_STATS_LOCATION, traceId);
    }
}
