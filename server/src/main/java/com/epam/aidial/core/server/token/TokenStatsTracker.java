package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import io.vertx.core.Future;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

import static com.epam.aidial.core.storage.resource.ResourceDescriptor.PATH_SEPARATOR;

@Slf4j
@RequiredArgsConstructor
public class TokenStatsTracker {
    public static final String DEPLOYMENT_COST_STATS_BUCKET = "deployment_cost_stats";
    public static final String DEPLOYMENT_COST_STATS_LOCATION = DEPLOYMENT_COST_STATS_BUCKET + PATH_SEPARATOR;

    private final AsyncTaskExecutor taskExecutor;
    private final ResourceService resourceService;

    /**
     * Starts current span.
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     */
    public Future<Void> startSpan(ProxyContext context) {
        return taskExecutor.submit(() -> {
            ResourceDescriptor resource = toResource(context.getTraceId());
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    traceContext = new TraceContext();
                }
                traceContext.addSpan(context);
                return ProxyUtil.convertToString(traceContext);
            });
            return null;
        });
    }

    public Future<TokenUsage> getTokenStats(ProxyContext context) {
        return taskExecutor.submit(() -> {
            ResourceDescriptor resource = toResource(context.getTraceId());
            String json = resourceService.getResource(resource);
            TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
            if (traceContext == null) {
                return null;
            }
            return traceContext.getStats(context);
        });
    }

    /**
     * Ends current span.
     */
    public Future<Void> endSpan(ProxyContext context) {
        ApiKeyData apiKeyData = context.getApiKeyData();
        if (apiKeyData.getPerRequestKey() == null) {
            return taskExecutor.submit(() -> {
                ResourceDescriptor resource = toResource(context.getTraceId());
                resourceService.deleteResource(resource, EtagHeader.ANY);
                return null;
            });
        } else {
            // we don't need to remove the span from trace context right now.
            // we can do it later when the initial span is completed
            return Future.succeededFuture();
        }
    }

    public Future<TokenUsage> updateModelStats(ProxyContext context) {
        ResourceDescriptor resource = toResource(context.getTraceId());
        return taskExecutor.submit(() -> {
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    return null;
                }
                traceContext.updateStats(context.getSpanId(), context.getTokenUsage());
                return ProxyUtil.convertToString(traceContext);
            });
            return context.getTokenUsage();
        });
    }

    /** Updates the token stats for a span identified by traceId/spanId — used when completing background jobs. */
    public Future<Void> updateStats(String traceId, String spanId, TokenUsage tokenUsage) {
        ResourceDescriptor resource = toResource(traceId);
        return taskExecutor.submit(() -> {
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    return null;
                }
                traceContext.updateStats(spanId, tokenUsage);
                return ProxyUtil.convertToString(traceContext);
            });
            return null;
        });
    }

    /** Deletes the root trace context — called when the root span of a background job completes. */
    public Future<Void> endRootSpan(String traceId) {
        ResourceDescriptor resource = toResource(traceId);
        return taskExecutor.submit(() -> {
            resourceService.deleteResource(resource, EtagHeader.ANY);
            return null;
        });
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
            String parentSpanId = tokenStats.parentSpanId;
            while (parentSpanId != null) {
                tokenStats = spans.get(parentSpanId);
                if (tokenStats == null) {
                    log.warn("Parent span {} was not added to the trace context.", parentSpanId);
                    break;
                }
                tokenStats.tokenUsage.increase(tokenUsage);
                parentSpanId = tokenStats.parentSpanId;
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

    private static ResourceDescriptor toResource(String traceId) {
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.DEPLOYMENT_COST_STATS, DEPLOYMENT_COST_STATS_BUCKET, DEPLOYMENT_COST_STATS_LOCATION, traceId);
    }
}
