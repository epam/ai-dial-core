package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.TokenUsageParser;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.RequestOptions;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.InputStream;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;

@Slf4j
@AllArgsConstructor
public class BaseDeploymentPostController {
    private static final Set<Integer> DEFAULT_RETRIABLE_HTTP_CODES = Set.of(HttpStatus.TOO_MANY_REQUESTS.getCode(),
            HttpStatus.BAD_GATEWAY.getCode(), HttpStatus.GATEWAY_TIMEOUT.getCode(),
            HttpStatus.SERVICE_UNAVAILABLE.getCode());

    protected final Proxy proxy;
    protected final ProxyContext context;

    protected BufferingReadStream createResponseStream(HttpClientResponse proxyResponse,
                                                       Supplier<BufferingReadStream.BaseEventListener> listenerSupplier) {
        BufferingReadStream.BaseEventListener eventListener = null;
        if (isEventStreamResponse(proxyResponse)) {
            eventListener = listenerSupplier.get();
        }
        return new BufferingReadStream(proxyResponse, ProxyUtil.contentLength(proxyResponse, 1024), eventListener);
    }

    protected boolean isEventStreamResponse(HttpClientResponse proxyResponse) {
        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        return Strings.CI.contains(contentType, "text/event-stream") && context.isStreamingRequest();
    }

    protected Future<Void> collectResponseAttachments(Buffer responseBody, CollectResponseAttachmentsFn fn) {
        if (isEventStreamResponse(context.getProxyResponse())) {
            return Future.succeededFuture();
        }
        try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            return fn.apply(tree).map(ignored -> null);
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
        ErrorData response = new ErrorData();
        response.getError().setCode(String.valueOf(HttpStatus.BAD_GATEWAY));
        String errorMessage = "Failed to connect to upstream server";
        response.getError().setMessage(errorMessage);
        response.getError().setDisplayMessage(errorMessage);
        respond(HttpStatus.BAD_GATEWAY, response);
        log.warn("Can't connect to origin.  Deployment: {}. Address: {}. Error: {}",
                context.getDeployment().getName(),
                context.getDeployment().getEndpoint(), error.getMessage());
    }

    protected void handleRequestBodyError(Throwable error) {
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
        log.warn("Failed to receive client body. Error: {}", error.getMessage());
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    protected void handleResponseError(Throwable error, BufferingReadStream responseStream) {
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        log.warn("Can't send response to client. Error:", error);
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Model) {
            // make sure we collect token usage in case if client accidentally closed the connection
            responseStream.endStreamFuture()
                    .onFailure(ignore -> context.getProxyRequest().reset()) // drop connection to stop origin response
                    .compose(ignore -> {
                        Buffer responseBody = responseStream.getContent();
                        context.setResponseBody(responseBody);
                        context.setResponseBodyTimestamp(System.currentTimeMillis());
                        return collectTokenUsage(responseBody);
                    })
                    .onSuccess(ignored -> proxy.getLogStore().save(AnalyticsLogContext.from(context)))
                    .onComplete(ignored -> finalizeRequest());
        } else {
            // drop connection to stop application responding
            context.getProxyRequest().reset();
        }
    }

    protected Future<Void> collectTokenUsage(Buffer responseBody) {
        Future<Void> tokenUsageFuture = Future.succeededFuture();
        if (context.getDeployment() instanceof Model model) {
            if (context.getResponse().getStatusCode() == HttpStatus.OK.getCode()) {
                TokenUsage tokenUsage = parseTokenUsage(responseBody);
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
                String bucket = BucketBuilder.buildInitiatorBucket(context);
                TokenUsage usage = context.getTokenUsage();
                tokenUsageFuture = proxy.getRateLimiter().increase(
                        context.getDeployment(), bucket, usage, context.getRequestBody(), context.getResponseBody())
                        .transform(result -> {
                            if (result.failed()) {
                                log.warn("Failed to increase limit", result.cause());
                            }
                            String traceId = context.getTraceId();
                            String spanId = context.getSpanId();
                            if (traceId != null && spanId != null) {
                                return proxy.getTokenStatsTracker().updateModelStats(traceId, spanId, usage);
                            }
                            return Future.succeededFuture();
                        });
            }
        } else {
            tokenUsageFuture = proxy.getTokenStatsTracker().getTokenStats(context)
                    .andThen(result -> context.setTokenUsage(result.result()))
                    .mapEmpty();
        }
        return tokenUsageFuture;
    }

    /**
     * Parses token usage from the fully buffered response body. Overridable so provider-specific
     * controllers can supply their own accounting (e.g. the Anthropic Messages API).
     */
    protected TokenUsage parseTokenUsage(Buffer responseBody) {
        return TokenUsageParser.parse(responseBody);
    }

    /**
     * Low-level: build and issue the proxy request to an already-resolved absolute URI.
     */
    protected Future<HttpClientRequest> createProxyRequest(String absoluteUri) {
        HttpServerRequest request = context.getRequest();
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(absoluteUri)
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation())
                .setConnectTimeout(proxy.getClientOptions().getConnectTimeout())
                .setIdleTimeout(proxy.getClientOptions().getIdleTimeout());

        return proxy.getClient().request(options);
    }

    /**
     * Dual-mode: route by interface type. When the deployment declares an {@code interfaces} base URL
     * for the type, forward to {@code base_url + <exact ingress path>}; otherwise forward the verbatim
     * legacy endpoint (plus the original query), byte-identical to the legacy flow.
     */
    protected Future<HttpClientRequest> createProxyRequest(InterfaceType type) {
        HttpServerRequest request = context.getRequest();
        Deployment deployment = context.getDeployment();
        String baseUrl = deployment.getInterfaceBaseUrl(type);
        String uri = baseUrl != null
                // New flow: base_url + exact ingress path. request.uri() already includes path + query.
                ? baseUrl + request.uri()
                // Legacy flow: verbatim endpoint + original query — byte-identical to today.
                : deployment.getLegacyEndpoint(type)
                        + (request.query() == null ? "" : "?" + request.query());
        return createProxyRequest(uri);
    }

    protected Future<HttpClientResponse> sendProxyRequest(
            HttpClientRequest proxyRequest, Function<Upstream, String> upstreamSelector) {
        log.info("Connected to origin. Deployment: {}. Address: {}",
                context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!context.getDeployment().isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }
        excludeHeaders.add(HEADER_APPLICATION_PROPERTIES, "whatever");
        excludeHeaders.add(HEADER_APPLICATION_ID, "whatever");

        ProxyUtil.copyHeaders(context.getRequest().headers(), proxyRequest.headers(), excludeHeaders);
        ProxyUtil.setOverrideNameHeader(proxyRequest, context.getDeployment());

        proxyRequest.headers().add(Proxy.HEADER_API_KEY, context.getProxyApiKeyData().getPerRequestKey());

        if (context.getDeployment() instanceof Model model && !model.getUpstreams().isEmpty()) {
            Upstream upstream = Objects.requireNonNull(context.getUpstreamRoute().get());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstreamSelector.apply(upstream))
                    .putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey())
                    .putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, UpstreamExtraDataMerger.merge(upstream))
                    .putHeader(Proxy.HEADER_CACHE_BREAKPOINT_PATH, context.getUpstreamRoute().getBreakpointPath())
                    .putHeader(Proxy.HEADER_CACHE_EXTRA_METADATA, context.getUpstreamRoute().getExtraMetadata());
        }

        if (context.getDeployment() instanceof Application application && application.hasApplicationTypeSchemaId()) {
            proxyRequest.putHeader(HEADER_APPLICATION_ID, context.getDeployment().getName());

            proxy.getApplicationSchemaService().consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
                if (appendApplicationPropertiesHeader) {
                    String propsString = ProxyUtil.MAPPER.writeValueAsString(properties);
                    proxyRequest.putHeader(HEADER_APPLICATION_PROPERTIES, propsString);
                }
            });
        }

        enrichProxyRequestHeaders(proxyRequest);

        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(context.getRequestBody().length()));

        return proxyRequest.send(context.getRequestBody());
    }

    /**
     * Hook for route-specific headers. Invoked after the client headers have been copied, so anything set
     * here replaces the value a client may have sent under the same name.
     */
    protected void enrichProxyRequestHeaders(HttpClientRequest proxyRequest) {
        // no additional headers by default
    }

    protected boolean isRetriableError(int statusCode) {
        return DEFAULT_RETRIABLE_HTTP_CODES.contains(statusCode)
                || context.getConfig().getRetriableErrorCodes().contains(statusCode);
    }

    protected boolean nextUpstream() {
        UpstreamRoute route = context.getUpstreamRoute();
        try {
            route.next();
            return true;
        } catch (HttpException e) {
            respond(e);
            log.warn("No route. Deployment: {}", context.getDeployment().getName());
        }

        return false;
    }
}
