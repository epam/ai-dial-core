package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.TokenUsageParser;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
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

    protected Future<HttpClientRequest> createProxyRequest(InterfaceType type) {
        HttpServerRequest request = context.getRequest();
        String baseUrl = context.getDeployment().getInterfaceBaseUrl(type);
        String uri = joinBaseUrlAndPath(baseUrl, request.uri());
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation())
                .setConnectTimeout(proxy.getClientOptions().getConnectTimeout())
                .setIdleTimeout(proxy.getClientOptions().getIdleTimeout());

        return proxy.getClient().request(options);
    }

    /**
     * Joins an interface {@code base_url} with a request path: strips trailing slashes from the base,
     * collapses leading slashes on the path to exactly one. e.g. {@code http://a:5000/} + {@code //x}
     * → {@code http://a:5000/x}; {@code http://a:5000/to-responses} + {@code /openai/v1/responses}
     * → {@code http://a:5000/to-responses/openai/v1/responses}.
     */
    static String joinBaseUrlAndPath(String baseUrl, String path) {
        String base = baseUrl == null ? "" : baseUrl;
        int end = base.length();
        while (end > 0 && base.charAt(end - 1) == '/') {
            end--;
        }
        base = base.substring(0, end);

        String suffix = path == null ? "" : path.replaceFirst("^/+", "/");
        if (!suffix.startsWith("/")) {
            suffix = "/" + suffix;
        }
        return base + suffix;
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

        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(context.getRequestBody().length()));

        return proxyRequest.send(context.getRequestBody());
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
