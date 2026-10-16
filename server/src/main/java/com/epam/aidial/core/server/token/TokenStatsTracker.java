package com.epam.aidial.core.server.token;

import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.blobstore.BlobStorageUtil;
import com.epam.aidial.core.storage.service.LockService;
import com.epam.aidial.core.storage.tracing.BlockingCallTracer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.vertx.core.Future;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@RequiredArgsConstructor
public class TokenStatsTracker {
    // not "deployment_cost_stats:": ResourceService keys its Redis hashes "<resource type>:<path>"
    static final String KEY_PREFIX = "deployment_cost_stats_tmp:";

    private final AsyncTaskExecutor taskExecutor;
    private final RedissonClient redis;
    private final LockService lockService;
    private final String prefix;
    private final BlockingCallTracer blockingCallTracer;
    // refreshed on every write: bounds records nobody deletes, and a request that reports its usage later than this
    // after the trace's last write loses it
    private final Duration ttl;
    // for a trace with a background Responses job, which outlives its HTTP request
    private final Duration backgroundTtl;

    /**
     * Adds the request to the usage record of its trace, creating the record if it does not exist.
     *
     * <p>The record is kept in Redis only, with a TTL, and is deleted when the root span ends.
     * Losing it to eviction under a {@code volatile-*} policy or to a Redis restart loses the trace's roll-up;
     * the costs each deployment reports for itself are not affected.
     */
    public Future<Void> startSpan(ProxyContext context) {
        return taskExecutor.submit(() -> {
            update(context.getTraceId(), true, traceContext -> {
                traceContext.addSpan(context);
                // a child may start a background job too: the poller reports its usage when the job ends
                if (context.isBackgroundJob()) {
                    traceContext.background = true;
                }
                return true;
            });
            return null;
        });
    }

    public Future<TokenUsage> getTokenStats(ProxyContext context) {
        return getUsageStats(context).map(UsageStats::total);
    }

    public Future<UsageStats> getUsageStats(ProxyContext context) {
        return taskExecutor.submit(() -> {
            String json = blockingCallTracer.trace("cost_stats.get", () -> getBucket(context.getTraceId()).get());
            TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
            if (traceContext == null) {
                return UsageStats.EMPTY;
            }
            return traceContext.getUsageStats(context.getSpanId());
        });
    }

    /**
     * Ends the root span of the trace: deletes its usage record.
     */
    public Future<Void> endSpan(String traceId) {
        return taskExecutor.submit(() -> blockingCallTracer.trace("cost_stats.delete", () -> {
            RBucket<String> bucket = getBucket(traceId);
            // under the lock, so a racing update cannot write back the deleted record
            try (var ignore = lockService.lock(bucket.getName())) {
                bucket.delete();
            }
            return null;
        }));
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
     * {@link UsageStats} for {@code spanId} itself - including the aggregated cost increments
     * collected for every ancestor along the way - computed inside the same locked
     * read-modify-write so callers never need a separate read.
     */
    public Future<UsageStats> updateDeploymentStats(String traceId, String spanId, String deploymentName, TokenUsage tokenUsage) {
        return taskExecutor.submit(() -> {
            UsageStats result = update(traceId, false, traceContext -> {
                if (!traceContext.spans.containsKey(spanId)) {
                    return null;
                }
                List<AggregatedCost> aggregatedCosts = traceContext.updateStats(spanId, deploymentName, tokenUsage);
                UsageStats usageStats = traceContext.getUsageStats(spanId);
                return new UsageStats(usageStats.total(), usageStats.usagePerModel(), aggregatedCosts);
            });
            if (result == null) {
                log.warn("Usage of span {} is lost: trace {} has no record holding it", spanId, traceId);
                return UsageStats.EMPTY;
            }
            return result;
        });
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TraceContext {
        Map<String, TokenStats> spans = new HashMap<>();
        // a span of the trace is a background Responses job: the record gets the background TTL
        boolean background;

        void addSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            String parentSpanId = context.getParentSpanId();
            String deploymentName = context.getDeployment() == null ? null : context.getDeployment().getName();
            TokenStats tokenStats = new TokenStats(new TokenUsage(), parentSpanId, deploymentName);
            spans.put(spanId, tokenStats);
        }

        UsageStats getUsageStats(String spanId) {
            TokenStats tokenStats = spans.get(spanId);
            if (tokenStats == null) {
                return UsageStats.EMPTY;
            }
            return new UsageStats(tokenStats.tokenUsage, toUsagePerModelList(tokenStats.usagePerModel), List.of());
        }

        /**
         * Rolls the reporting span's usage into every ancestor's subtree aggregate and per-model
         * breakdown, and returns the aggregated-cost increment for each ancestor along the way.
         * The mechanism is deployment-kind-agnostic: every span visited here is, by definition, an
         * ancestor with a descendant call, whatever kind of deployment it turns out to be.
         */
        List<AggregatedCost> updateStats(String spanId, String deploymentName, TokenUsage tokenUsage) {
            TokenStats tokenStats = spans.get(spanId);
            if (tokenStats == null) {
                return List.of();
            }
            // self: the reporting deployment's own usage replaces whatever was here, except
            // aggCost, which keeps accumulating (a descendant may have already rolled its
            // cost into this span before this deployment self-reported). usagePerModel is
            // not self-merged: a deployment's own usage is already visible via tokenUsage,
            // so its own breakdown only needs to cover what its descendants contributed.
            tokenStats.tokenUsage.assign(tokenUsage);

            List<AggregatedCost> aggregatedCosts = new ArrayList<>();
            BigDecimal delta = tokenUsage.getAggCost();
            String parentSpanId = tokenStats.parentSpanId;
            while (parentSpanId != null) {
                tokenStats = spans.get(parentSpanId);
                if (tokenStats == null) {
                    log.warn("Parent span {} was not added to the trace context.", parentSpanId);
                    break;
                }
                // ancestors: only aggCost and the per-model breakdown roll up - raw token
                // counts are never accumulated into an ancestor's own tokenUsage.
                tokenStats.tokenUsage.increaseAggCost(delta);
                addUsagePerModel(tokenStats, deploymentName, tokenUsage);
                if (tokenStats.deploymentName != null && delta != null && delta.signum() > 0) {
                    aggregatedCosts.add(new AggregatedCost(tokenStats.deploymentName, delta));
                }
                parentSpanId = tokenStats.parentSpanId;
            }
            return aggregatedCosts;
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
        String deploymentName;
        List<ModelTokenUsage> usagePerModel = new ArrayList<>();

        public TokenStats() {
        }

        public TokenStats(TokenUsage tokenUsage, String parentSpanId, String deploymentName) {
            this.tokenUsage = tokenUsage;
            this.parentSpanId = parentSpanId;
            this.deploymentName = deploymentName;
        }
    }

    /**
     * Aggregated-cost increment for one ancestor span, kind-agnostic: whatever kind of deployment
     * {@code deploymentName} turns out to be, it is credited the same way once it has a descendant
     * call rolling cost into it.
     */
    public record AggregatedCost(String deploymentName, BigDecimal cost) {
    }

    /**
     * @param total scalar subtree aggregate (drives cost/aggCost propagation), as before.
     * @param usagePerModel one entry per self-report from a descendant, indexed in report order;
     *                       repeated reports from the same deployment name are not merged.
     * @param aggregatedCosts aggregated-cost increment for every ancestor visited by this report,
     *                         empty for a pure read (nothing new is "known" outside an update).
     */
    public record UsageStats(TokenUsage total, List<UsagePerModel> usagePerModel, List<AggregatedCost> aggregatedCosts) {
        public static final UsageStats EMPTY = new UsageStats(null, List.of(), List.of());
    }

    /**
     * Applies {@code fn} to the usage record of the trace under its lock and stores the result with a fresh TTL.
     *
     * @return the result of {@code fn}, or {@code null} without writing if there is no record and {@code create}
     *     is false, or if {@code fn} returns {@code null}.
     */
    private <T> T update(String traceId, boolean create, Function<TraceContext, T> fn) {
        return blockingCallTracer.trace("cost_stats.update", () -> {
            RBucket<String> bucket = getBucket(traceId);
            try (var ignore = lockService.lock(bucket.getName())) {
                TraceContext traceContext = ProxyUtil.convertToObject(bucket.get(), TraceContext.class);
                if (traceContext == null && !create) {
                    return null;
                }
                if (traceContext == null) {
                    traceContext = new TraceContext();
                }
                T result = fn.apply(traceContext);
                if (result != null) {
                    bucket.set(ProxyUtil.convertToString(traceContext), traceContext.background ? backgroundTtl : ttl);
                }
                return result;
            }
        });
    }

    private RBucket<String> getBucket(String traceId) {
        return redis.getBucket(KEY_PREFIX + BlobStorageUtil.toStoragePath(prefix, traceId), StringCodec.INSTANCE);
    }
}
