package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.service.ResourceService;
import com.epam.aidial.core.storage.util.EtagHeader;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class TokenStatsTracker {
    // Declared in ResourceDescriptor so a storage layout can enumerate the system buckets; a second literal
    // here is how the tenant-rooted layout came to reject this location in the first place.
    public static final String DEPLOYMENT_COST_STATS_BUCKET = ResourceDescriptor.DEPLOYMENT_COST_STATS_BUCKET;
    public static final String DEPLOYMENT_COST_STATS_LOCATION = ResourceDescriptor.DEPLOYMENT_COST_STATS_LOCATION;

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
        return getUsageStats(context).map(UsageStats::total);
    }

    public Future<UsageStats> getUsageStats(ProxyContext context) {
        return taskExecutor.submit(() -> {
            ResourceDescriptor resource = toResource(context.getTraceId());
            String json = resourceService.getResource(resource);
            TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
            if (traceContext == null) {
                return UsageStats.EMPTY;
            }
            return traceContext.getUsageStats(context.getSpanId());
        });
    }

    public Future<Void> endSpan(String traceId) {
        ResourceDescriptor resource = toResource(traceId);
        return taskExecutor.submit(() -> {
            resourceService.deleteResource(resource, EtagHeader.ANY);
            return null;
        });
    }

    /**
     * Ends current span.
     */
    public Future<Void> endSpan(ProxyContext context) {
        if (context.isOriginalRequest()) {
            return endSpan(context.getTraceId());
        } else {
            // we don't need to remove the span from trace context right now.
            // we can do it later when the initial span is completed
            return Future.succeededFuture();
        }
    }

    /**
     * Records usage self-reported by a deployment (Model or Application) and rolls it into the
     * subtree aggregate and per-deployment breakdown of every ancestor. Returns the resulting
     * {@link UsageStats} for {@code spanId} itself, computed inside the same locked
     * read-modify-write so callers never need a separate read.
     */
    public Future<UsageStats> updateDeploymentStats(String traceId, String spanId, String deploymentName, TokenUsage tokenUsage) {
        ResourceDescriptor resource = toResource(traceId);
        return taskExecutor.submit(() -> {
            UsageStats[] result = {UsageStats.EMPTY};
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    return null;
                }
                traceContext.updateStats(spanId, deploymentName, tokenUsage);
                result[0] = traceContext.getUsageStats(spanId);
                return ProxyUtil.convertToString(traceContext);
            });
            return result[0];
        });
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TraceContext {
        Map<String, TokenStats> spans = new HashMap<>();

        void addSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            String parentSpanId = context.getParentSpanId();
            TokenStats tokenStats = new TokenStats(new TokenUsage(), parentSpanId);
            spans.put(spanId, tokenStats);
        }

        UsageStats getUsageStats(String spanId) {
            TokenStats tokenStats = spans.get(spanId);
            if (tokenStats == null) {
                return UsageStats.EMPTY;
            }
            return new UsageStats(tokenStats.tokenUsage, toUsagePerModelList(tokenStats.usagePerModel));
        }

        void updateStats(String spanId, String deploymentName, TokenUsage tokenUsage) {
            TokenStats tokenStats = spans.get(spanId);
            if (tokenStats == null) {
                return;
            }
            // self: the reporting deployment's own usage replaces whatever was here, except
            // aggCost, which keeps accumulating (a descendant may have already rolled its
            // cost into this span before this deployment self-reported). usagePerModel is
            // not self-merged: a deployment's own usage is already visible via tokenUsage,
            // so its own breakdown only needs to cover what its descendants contributed.
            tokenStats.tokenUsage.assign(tokenUsage);

            String parentSpanId = tokenStats.parentSpanId;
            while (parentSpanId != null) {
                tokenStats = spans.get(parentSpanId);
                if (tokenStats == null) {
                    log.warn("Parent span {} was not added to the trace context.", parentSpanId);
                    break;
                }
                // ancestors: only aggCost and the per-model breakdown roll up - raw token
                // counts are never accumulated into an ancestor's own tokenUsage.
                tokenStats.tokenUsage.increaseAggCost(tokenUsage.getAggCost());
                addUsagePerModel(tokenStats, deploymentName, tokenUsage);
                parentSpanId = tokenStats.parentSpanId;
            }
        }

        /**
         * Appends this report as a new entry - no merge-by-name: two reports from the same
         * deployment name produce two separate entries, each carrying only that one report's usage.
         */
        private static void addUsagePerModel(TokenStats tokenStats, String deploymentName, TokenUsage tokenUsage) {
            // own copy, never the caller's reference: this node and every ancestor must be
            // able to accumulate independently without aliasing each other
            TokenUsage copy = new TokenUsage();
            copy.increase(tokenUsage);
            tokenStats.usagePerModel.add(new ModelTokenUsage(deploymentName, copy));
        }

        private static List<UsagePerModel> toUsagePerModelList(List<ModelTokenUsage> usagePerModel) {
            List<UsagePerModel> result = new ArrayList<>(usagePerModel.size());
            for (ModelTokenUsage entry : usagePerModel) {
                result.add(new UsagePerModel(result.size(), entry.getModel(), entry.getUsage()));
            }
            return result;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ModelTokenUsage {
        String model;
        TokenUsage usage;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenStats {
        TokenUsage tokenUsage;
        String parentSpanId;
        List<ModelTokenUsage> usagePerModel = new ArrayList<>();

        public TokenStats() {
        }

        public TokenStats(TokenUsage tokenUsage, String parentSpanId) {
            this.tokenUsage = tokenUsage;
            this.parentSpanId = parentSpanId;
        }
    }

    /**
     * @param total scalar subtree aggregate (drives cost/aggCost propagation), as before.
     * @param usagePerModel one entry per self-report from a descendant, indexed in report order;
     *                       repeated reports from the same deployment name are not merged.
     */
    public record UsageStats(TokenUsage total, List<UsagePerModel> usagePerModel) {
        public static final UsageStats EMPTY = new UsageStats(null, List.of());
    }

    private static ResourceDescriptor toResource(String traceId) {
        return ResourceDescriptorFactory.fromDecoded(
                ResourceTypes.DEPLOYMENT_COST_STATS, DEPLOYMENT_COST_STATS_BUCKET, DEPLOYMENT_COST_STATS_LOCATION, traceId);
    }
}
