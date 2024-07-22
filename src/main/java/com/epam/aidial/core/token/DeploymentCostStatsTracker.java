package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.PricingUnit;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.EtagHeader;
import com.epam.aidial.core.util.ModelCostCalculator;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;
import static com.epam.aidial.core.util.ProxyUtil.isStreamingResponseCompleted;

@Slf4j
@RequiredArgsConstructor
public class DeploymentCostStatsTracker {

    public static final String DEPLOYMENT_COST_STATS_BUCKET = "deployment_cost_stats";
    public static final String DEPLOYMENT_COST_STATS_LOCATION = DEPLOYMENT_COST_STATS_BUCKET + PATH_SEPARATOR;
    private final ResourceService resourceService;
    private final Vertx vertx;

    /**
     * Starts current span.
     * <p>
     *     Note. The method is blocking and shouldn't be run in the event loop thread.
     * </p>
     */
    public void startSpan(ProxyContext context) {
        ResourceDescription resource = toResource(context.getTraceId());
        resourceService.computeResource(resource, json -> {
            TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
            if (traceContext == null) {
                traceContext = new TraceContext();
            }
            traceContext.addSpan(context);
            return ProxyUtil.convertToString(traceContext);
        });
    }

    public Future<DeploymentCostStats> getDeploymentStats(ProxyContext context) {
        try {
            ResourceDescription resource = toResource(context.getTraceId());
            return vertx.executeBlocking(() -> {
                String json = resourceService.getResource(resource);
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    return null;
                }
                return traceContext.getStats(context);
            }, false);
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    /**
     * Ends current span.
     */
    public Future<Void> endSpan(ProxyContext context) {
        try {
            ApiKeyData apiKeyData = context.getApiKeyData();
            if (apiKeyData.getPerRequestKey() == null) {
                ResourceDescription resource = toResource(context.getTraceId());
                return vertx.executeBlocking(() -> {
                    resourceService.deleteResource(resource, EtagHeader.ANY);
                    return null;
                }, false);
            } else {
                // we don't need to remove the span from trace context right now.
                // we can do it later when the initial span is completed
                return Future.succeededFuture();
            }
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    public Future<Void> handleChunkResponse(Buffer chunk, ProxyContext context) {
        try {
            Deployment deployment = context.getDeployment();
            if (deployment instanceof Model model) {
                int index = ProxyUtil.findFirstIndexOfContentInStreamingResponse(chunk);
                boolean isStreamingResponse = index != -1;
                boolean isStreamingResponseCompleted = isStreamingResponse && isStreamingResponseCompleted(chunk, index);
                Pricing pricing = model.getPricing();
                if (pricing == null) {
                    return Future.succeededFuture();
                }
                switch (pricing.getUnit()) {
                    case TOKEN -> collectTokenUsage(chunk, isStreamingResponse, context);
                    case CHAR_WITHOUT_WHITESPACE -> {
                        if (!isStreamingResponseCompleted) {
                            collectResponseLength(chunk, index, context, model.getType());
                        }
                    }
                    default -> log.warn("Unsupported pricing unit {}", pricing.getUnit());
                }
                if (isStreamingResponse) {
                    if (isStreamingResponseCompleted(chunk, index)) {
                        return completeModelResponse(context);
                    }
                } else {
                    return completeModelResponse(context);
                }
            }
            return Future.succeededFuture();
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private void collectResponseLength(Buffer chunk, int index, ProxyContext context, ModelType modelType) {
        Buffer content;
        if (index != -1) {
            content = chunk.slice(index, chunk.length());
        } else {
            content = chunk;
        }
        boolean isStreamingResponse = index != -1;
        int responseLength = ModelCostCalculator.getResponseContentLength(modelType, content, isStreamingResponse);
        context.getDeploymentCostStats().setResponseContentLength(responseLength);
    }

    private void collectTokenUsage(Buffer chunk, boolean isStreamingResponse, ProxyContext context) {
        TokenUsage tokenUsage = TokenUsageParser.parse(chunk);
        if (tokenUsage == null) {
            if (!isStreamingResponse) {
                log.warn("Can't find token usage. Trace: {}. Span: {}. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}",
                        context.getTraceId(), context.getSpanId(),
                        context.getProject(), context.getDeployment().getName(),
                        context.getDeployment().getEndpoint(),
                        context.getUpstreamRoute().get().getEndpoint(),
                        context.getResponse().getStatusCode(),
                        context.getResponseBody().length());
            }
        } else {
            context.getDeploymentCostStats().setTokenUsage(tokenUsage);
        }
    }

    public void handleRequestBody(ObjectNode requestBody, ProxyContext context) {
        try {
            DeploymentCostStats deploymentCostStats = new DeploymentCostStats();
            context.setDeploymentCostStats(deploymentCostStats);
            Deployment deployment = context.getDeployment();
            if (deployment instanceof Model model) {
                Pricing pricing = model.getPricing();
                if (pricing == null) {
                    return;
                }
                PricingUnit unit = pricing.getUnit();
                if (unit == PricingUnit.CHAR_WITHOUT_WHITESPACE) {
                    int requestLength = ModelCostCalculator
                            .getRequestContentLength(model.getType(), requestBody);
                    deploymentCostStats.setRequestContentLength(requestLength);
                }
            }
        } catch (Throwable e) {
            log.error("Error occurred at reading model cost stats from request", e);
        }
    }

    private Future<Void> completeModelResponse(ProxyContext context) {
        DeploymentCostStats deploymentStats = context.getDeploymentCostStats();
        try {
            BigDecimal cost = ModelCostCalculator.calculate(context);
            deploymentStats.setCost(cost);
            deploymentStats.setAggCost(cost);
        } catch (Throwable e) {
            log.warn("Failed to calculate cost for model={}. Trace: {}. Span: {}",
                    context.getDeployment().getName(), context.getTraceId(), context.getSpanId(), e);
        }
        ResourceDescription resource = toResource(context.getTraceId());
        return vertx.executeBlocking(() -> {
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    return null;
                }
                updateStatsOnParents(traceContext, deploymentStats, context);
                return ProxyUtil.convertToString(traceContext);
            });
            return null;
        }, false);
    }

    private void updateStatsOnParents(TraceContext traceContext, DeploymentCostStats deploymentCostStats, ProxyContext context) {
        DeploymentStats deploymentStats = traceContext.spans.get(context.getSpanId());
        if (deploymentStats == null) {
            return;
        }
        deploymentStats.deploymentCostStats = deploymentCostStats;
        String parenSpanId = deploymentStats.parentSpanId;
        while (parenSpanId != null) {
            deploymentStats = traceContext.spans.get(parenSpanId);
            deploymentStats.deploymentCostStats.increase(deploymentCostStats);
            parenSpanId = deploymentStats.parentSpanId;
        }
    }

    @Data
    public static class TraceContext {
        Map<String, DeploymentStats> spans = new HashMap<>();

        void addSpan(ProxyContext context) {
            String spanId = context.getSpanId();
            String parentSpanId = context.getParentSpanId();
            DeploymentStats deploymentStats = new DeploymentStats(new DeploymentCostStats(), parentSpanId);
            spans.put(spanId, deploymentStats);
        }

        DeploymentCostStats getStats(ProxyContext context) {
            DeploymentStats deploymentStats = spans.get(context.getSpanId());
            if (deploymentStats == null) {
                return null;
            }
            return deploymentStats.deploymentCostStats;
        }
    }

    @Data
    public static class DeploymentStats {
        DeploymentCostStats deploymentCostStats;
        String parentSpanId;

        public DeploymentStats() {
        }

        public DeploymentStats(DeploymentCostStats deploymentCostStats, String parentSpanId) {
            this.deploymentCostStats = deploymentCostStats;
            this.parentSpanId = parentSpanId;
        }
    }

    private static ResourceDescription toResource(String traceId) {
        return ResourceDescription.fromDecoded(
                ResourceType.DEPLOYMENT_COST_STATS, DEPLOYMENT_COST_STATS_BUCKET, DEPLOYMENT_COST_STATS_LOCATION, traceId);
    }
}
