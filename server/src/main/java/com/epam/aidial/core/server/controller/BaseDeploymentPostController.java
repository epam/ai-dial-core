package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseChatCompletionAttachmentsFn;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.TokenUsageParser;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.InputStream;

@Slf4j
@AllArgsConstructor
public class BaseDeploymentPostController {

    protected final Proxy proxy;
    protected final ProxyContext context;

    protected BufferingReadStream createResponseStream(HttpClientResponse proxyResponse) {
        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        boolean isEventStreamResponse = Strings.CI.contains(contentType, "text/event-stream") && context.isStreamingRequest();
        CollectResponseAttachmentsFn handler = isEventStreamResponse ? new CollectResponseChatCompletionAttachmentsFn(proxy, context) : null;
        return new BufferingReadStream(proxyResponse, ProxyUtil.contentLength(proxyResponse, 1024), handler);
    }

    protected Future<Void> collectResponseAttachments(Buffer responseBody) {
        if (context.isStreamingRequest()) {
            return Future.succeededFuture();
        }
        try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            var fn = new CollectResponseChatCompletionAttachmentsFn(proxy, context);
            return fn.apply(tree);
        } catch (Throwable e) {
            log.warn("Can't parse JSON response body. Error:", e);
            return Future.failedFuture(e);
        }
    }

    protected Future<?> respond(HttpStatus status, String errorMessage) {
        finalizeRequest();
        return context.respond(status, errorMessage);
    }

    protected void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    protected void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    protected void respond(HttpStatus status, Object result) {
        finalizeRequest();
        context.respond(status, result);
    }

    protected void finalizeRequest() {
        proxy.getTokenStatsTracker().endSpan(context).onFailure(error -> log.error("Error occurred at completing span", error));
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(proxyApiKeyData)
                    .onSuccess(invalidated -> {
                        if (!invalidated) {
                            log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                        }
                    }).onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    protected void handleProxyConnectionError(Throwable error) {
        respond(HttpStatus.BAD_GATEWAY, "Failed to connect to origin");
        log.warn("Can't connect to origin.  Deployment: {}. Address: {}. Error: {}",
                context.getDeployment().getName(),
                context.getDeployment().getEndpoint(), error.getMessage());
    }

    protected void handleRequestBodyError(Throwable error) {
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
        log.warn("Failed to receive client body. Error: {}", error.getMessage());
    }

    protected Future<TokenUsage> collectTokenUsage(Buffer responseBody) {
        Future<TokenUsage> tokenUsageFuture = Future.succeededFuture();
        if (context.getDeployment() instanceof Model model) {
            if (context.getResponse().getStatusCode() == HttpStatus.OK.getCode()) {
                TokenUsage tokenUsage = TokenUsageParser.parse(responseBody);
                if (tokenUsage == null) {
                    Pricing pricing = model.getPricing();
                    if (pricing == null || "token".equals(pricing.getUnit())) {
                        Upstream currentUpstream = context.getUpstreamRoute().get();
                        log.warn("Can't find token usage. Deployment: {}. Endpoint: {}. Upstream: {}. Length: {}. Upstream.extraData: {}",
                                context.getDeployment().getName(),
                                context.getDeployment().getEndpoint(),
                                currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                                context.getResponseBody().length(),
                                currentUpstream == null ? "N/A" : currentUpstream.getExtraData());
                    }
                    tokenUsage = new TokenUsage();
                }
                context.setTokenUsage(tokenUsage);
                tokenUsageFuture = proxy.getRateLimiter().increase(context, context.getDeployment())
                        .transform(result -> {
                            if (result.failed()) {
                                log.warn("Failed to increase limit", result.cause());
                            }
                            return proxy.getTokenStatsTracker().updateModelStats(context);
                        });
            }
        } else {
            tokenUsageFuture = proxy.getTokenStatsTracker().getTokenStats(context).andThen(result -> context.setTokenUsage(result.result()));
        }
        return tokenUsageFuture;
    }
}
