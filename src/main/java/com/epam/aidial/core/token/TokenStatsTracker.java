package com.epam.aidial.core.token;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.service.ResourceService;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.ModelCostCalculator;
import com.epam.aidial.core.util.ProxyUtil;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static com.epam.aidial.core.storage.BlobStorageUtil.PATH_SEPARATOR;

@Slf4j
@RequiredArgsConstructor
public class TokenStatsTracker {

    public static final String TOKEN_USAGE_STATS_BUCKET = "api_key_data";
    public static final String TOKEN_USAGE_STATS_LOCATION = TOKEN_USAGE_STATS_BUCKET + PATH_SEPARATOR;
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

    public Future<TokenUsage> getTokenStats(ProxyContext context) {
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

    public Future<Void> endSpan(ProxyContext context) {
        try {
            ApiKeyData apiKeyData = context.getApiKeyData();
            if (apiKeyData.getPerRequestKey() == null) {
                ResourceDescription resource = toResource(context.getTraceId());
                return vertx.executeBlocking(() -> {
                    resourceService.deleteResource(resource);
                    return null;
                }, false);
            } else {
                return Future.succeededFuture();
            }
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    public Future<Void> handleChunkResponse(Buffer chunk, ProxyContext context) {
        try {
            int index = ProxyUtil.lastIndexOfStartStreamingToken(chunk);
            boolean isStreamingResponse = index != -1;
            TokenUsage tokenUsage = TokenUsageParser.parse(chunk);
            Model model = (Model) context.getDeployment();
            if (tokenUsage == null) {
                Pricing pricing = model.getPricing();
                if (!isStreamingResponse && (pricing == null || "token".equals(pricing.getUnit()))) {
                    log.warn("Can't find token usage. Trace: {}. Span: {}. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}",
                            context.getTraceId(), context.getSpanId(),
                            context.getProject(), context.getDeployment().getName(),
                            context.getDeployment().getEndpoint(),
                            context.getUpstreamRoute().get().getEndpoint(),
                            context.getResponse().getStatusCode(),
                            context.getResponseBody().length());
                }
            } else {
                context.setTokenUsage(tokenUsage);
                try {
                    BigDecimal cost = ModelCostCalculator.calculate(context);
                    tokenUsage.setCost(cost);
                    tokenUsage.setAggCost(cost);
                } catch (Throwable e) {
                    log.warn("Failed to calculate cost for model={}. Trace: {}. Span: {}",
                            context.getDeployment().getName(), context.getTraceId(), context.getSpanId(), e);
                }
            }
            if (isStreamingResponse) {
                if (isStreamingResponseCompleted(chunk, index)) { // complemented
                    return completeModelResponse(context);
                }
            } else {
                return completeModelResponse(context);
            }
            return Future.succeededFuture();
        } catch (Throwable e) {
            return Future.failedFuture(e);
        }
    }

    private Future<Void> completeModelResponse(ProxyContext context) {
        TokenUsage tokenUsage = context.getTokenUsage();
        if (tokenUsage == null) {
            return Future.succeededFuture();
        }
        ResourceDescription resource = toResource(context.getTraceId());
        return vertx.executeBlocking(() -> {
            resourceService.computeResource(resource, json -> {
                TraceContext traceContext = ProxyUtil.convertToObject(json, TraceContext.class);
                if (traceContext == null) {
                    return null;
                }
                updateStatsOnParents(traceContext, tokenUsage, context);
                return ProxyUtil.convertToString(traceContext);
            });
            return null;
        }, false);
    }

    private void updateStatsOnParents(TraceContext traceContext, TokenUsage tokenUsage, ProxyContext context) {
        TokenStats tokenStats = traceContext.spans.get(context.getSpanId());
        if (tokenStats == null) {
            return;
        }
        tokenStats.tokenUsage = tokenUsage;
        String parenSpanId = tokenStats.parentSpanId;
        while (parenSpanId != null) {
            tokenStats = traceContext.spans.get(parenSpanId);
            tokenStats.tokenUsage.increase(tokenUsage);
            parenSpanId = tokenStats.parentSpanId;
        }
    }

    private boolean isStreamingResponseCompleted(Buffer chunk, int index) {
        for (; index < chunk.length(); index++) {
            byte b = chunk.getByte(index);
            if (!Character.isWhitespace(b)) {
                break;
            }
        }
        String done = "[DONE]";
        int j = 0;
        for (; index < chunk.length() && j < done.length(); index++, j++) {
            if (done.charAt(j) != chunk.getByte(index)) {
                break;
            }
        }
        return j == done.length();
    }

    private static class TraceContext {
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
    }

    private static class TokenStats {
        TokenUsage tokenUsage;
        String parentSpanId;

        public TokenStats(TokenUsage tokenUsage, String parentSpanId) {
            this.tokenUsage = tokenUsage;
            this.parentSpanId = parentSpanId;
        }
    }

    private static ResourceDescription toResource(String traceId) {
        return ResourceDescription.fromDecoded(
                ResourceType.TOKEN_USAGE_STATS, TOKEN_USAGE_STATS_BUCKET, TOKEN_USAGE_STATS_LOCATION, traceId);
    }
}
