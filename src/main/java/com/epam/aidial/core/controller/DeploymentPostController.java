package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.ApiKeyData;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.data.ErrorData;
import com.epam.aidial.core.function.BaseFunction;
import com.epam.aidial.core.function.CollectAttachmentsFn;
import com.epam.aidial.core.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.function.enhancement.EnhanceAssistantRequestFn;
import com.epam.aidial.core.function.enhancement.EnhanceModelRequestFn;
import com.epam.aidial.core.limiter.RateLimitResult;
import com.epam.aidial.core.token.TokenUsage;
import com.epam.aidial.core.token.TokenUsageParser;
import com.epam.aidial.core.upstream.DeploymentUpstreamProvider;
import com.epam.aidial.core.upstream.UpstreamProvider;
import com.epam.aidial.core.upstream.UpstreamRoute;
import com.epam.aidial.core.util.BufferingReadStream;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.ModelCostCalculator;
import com.epam.aidial.core.util.ProxyUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.annotations.VisibleForTesting;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

@Slf4j
public class DeploymentPostController {

    private static final Set<Integer> DEFAULT_RETRIABLE_HTTP_CODES = Set.of(HttpStatus.TOO_MANY_REQUESTS.getCode(),
            HttpStatus.BAD_GATEWAY.getCode(), HttpStatus.GATEWAY_TIMEOUT.getCode(),
            HttpStatus.SERVICE_UNAVAILABLE.getCode());

    private final Proxy proxy;
    private final ProxyContext context;

    private final List<BaseFunction<ObjectNode>> enhancementFunctions;

    public DeploymentPostController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.enhancementFunctions = List.of(new CollectAttachmentsFn(proxy, context),
                new ApplyDefaultDeploymentSettingsFn(proxy, context),
                new EnhanceAssistantRequestFn(proxy, context),
                new EnhanceModelRequestFn(proxy, context));
    }

    public Future<?> handle(String deploymentId, String deploymentApi) {
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (!StringUtils.containsIgnoreCase(contentType, Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Only application/json is supported");
        }

        Deployment deployment = context.getConfig().selectDeployment(deploymentId);
        if (!isValidDeploymentApi(deployment, deploymentApi)) {
            deployment = null;
        }

        if (deployment == null) {
            log.error("Deployment {} is not found", deploymentId);
            return context.respond(HttpStatus.NOT_FOUND, "Deployment is not found");
        }

        if (!isBaseAssistant(deployment) && !DeploymentController.hasAccess(context, deployment)) {
            log.error("Forbidden deployment {}. Key: {}. User sub: {}", deploymentId, context.getProject(), context.getUserSub());
            return context.respond(HttpStatus.FORBIDDEN, "Forbidden deployment");
        }

        context.setDeployment(deployment);

        Future<RateLimitResult> rateLimitResultFuture;
        if (deployment instanceof Model) {
            rateLimitResultFuture = proxy.getRateLimiter().limit(context);
        } else {
            rateLimitResultFuture = Future.succeededFuture(RateLimitResult.SUCCESS);
        }

        return rateLimitResultFuture.map((Function<RateLimitResult, Void>) result -> {
            if (result.status() == HttpStatus.OK) {
                handleRateLimitSuccess(deploymentId);
            } else {
                handleRateLimitHit(deploymentId, result);
            }
            return null;
        });
    }

    private void handleRateLimitSuccess(String deploymentId) {
        log.info("Received request from client. Trace: {}. Span: {}. Key: {}. Deployment: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getRequest().headers().size());

        UpstreamProvider endpointProvider = new DeploymentUpstreamProvider(context.getDeployment());
        UpstreamRoute endpointRoute = proxy.getUpstreamBalancer().balance(endpointProvider);
        context.setUpstreamRoute(endpointRoute);

        if (!endpointRoute.hasNext()) {
            log.error("No route. Trace: {}. Span: {}. Key: {}. Deployment: {}. User sub: {}",
                    context.getTraceId(), context.getSpanId(),
                    context.getProject(), deploymentId, context.getUserSub());

            respond(HttpStatus.BAD_GATEWAY, "No route");
            return;
        }

        setupProxyApiKeyData();
        proxy.getTokenStatsTracker().startSpan(context);

        context.getRequest().body()
                .onSuccess(body -> proxy.getVertx().executeBlocking(() -> {
                    handleRequestBody(body);
                    return null;
                }, false).onFailure(this::handleError))
                .onFailure(this::handleRequestBodyError);
    }

    /**
     * The method uses blocking calls and should not be used in the event loop thread.
     */
    private void setupProxyApiKeyData() {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    private void handleRateLimitHit(String deploymentId, RateLimitResult result) {
        // Returning an error similar to the Azure format.
        ErrorData rateLimitError = new ErrorData();
        rateLimitError.getError().setCode(String.valueOf(result.status().getCode()));
        rateLimitError.getError().setMessage(result.errorMessage());
        log.error("Rate limit error {}. Key: {}. User sub: {}. Deployment: {}. Trace: {}. Span: {}", result.errorMessage(),
                context.getProject(), context.getUserSub(), deploymentId, context.getTraceId(), context.getSpanId());
        respond(result.status(), rateLimitError);
    }

    private void handleError(Throwable error) {
        log.error("Can't handle request. Key: {}. User sub: {}. Trace: {}. Span: {}",
                context.getProject(), context.getUserSub(), context.getTraceId(), context.getSpanId(),  error);
        respond(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @SneakyThrows
    private void sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        if (!route.hasNext()) {
            log.error("No route. Trace: {}. Span: {}. Key: {}. Deployment: {}. User sub: {}",
                    context.getTraceId(), context.getSpanId(),
                    context.getProject(), context.getDeployment().getName(), context.getUserSub());

            respond(HttpStatus.BAD_GATEWAY, "No route");
            return;
        }

        Upstream upstream = route.next();
        Objects.requireNonNull(upstream);

        String uri = buildUri(context);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(uri)
                .setMethod(request.method());

        proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    @VisibleForTesting
    void handleRequestBody(Buffer requestBody) {
        Deployment deployment = context.getDeployment();
        log.info("Received body from client. Trace: {}. Span: {}. Key: {}. Deployment: {}. Length: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), deployment.getName(), requestBody.length());

        context.setRequestBody(requestBody);
        context.setRequestBodyTimestamp(System.currentTimeMillis());

        try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            Throwable error = ProxyUtil.processChain(tree, enhancementFunctions);
            if (error != null) {
                finalizeRequest();
                return;
            }
        } catch (IOException e) {
            respond(HttpStatus.BAD_REQUEST);
            log.warn("Can't parse JSON request body. Trace: {}. Span: {}. Error:",
                    context.getTraceId(), context.getSpanId(), e);
            return;
        }

        sendRequest();
    }

    /**
     * Called when proxy connected to the origin.
     */
    @VisibleForTesting
    void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin. Trace: {}. Span: {}. Key: {}. Deployment: {}. Address: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);
        context.setProxyConnectTimestamp(System.currentTimeMillis());

        Deployment deployment = context.getDeployment();
        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!deployment.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);

        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());

        if (context.getDeployment() instanceof Model model && !model.getUpstreams().isEmpty()) {
            Upstream upstream = context.getUpstreamRoute().get();
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, upstream.getEndpoint());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_KEY, upstream.getKey());
        }

        Buffer requestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(requestBody.length()));
        context.getRequestHeaders().forEach(proxyRequest::putHeader);

        proxyRequest.send(requestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyResponseError);
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        log.info("Received header from origin. Trace: {}. Span: {}. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Headers: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getDeployment().getEndpoint(), context.getUpstreamRoute().get().getEndpoint(),
                proxyResponse.statusCode(), proxyResponse.headers().size());

        if (context.getUpstreamRoute().hasNext() && isRetriableError(proxyResponse.statusCode())) {
            sendRequest(); // try next
            return;
        }

        BufferingReadStream responseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setProxyResponseTimestamp(System.currentTimeMillis());
        context.setResponseStream(responseStream);

        HttpServerResponse response = context.getResponse();

        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());

        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(context.getUpstreamRoute().used()));

        responseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse())
                .onFailure(this::handleResponseError);
    }

    private boolean isRetriableError(int statusCode) {
        return context.getUpstreamRoute().hasNext()
                && (DEFAULT_RETRIABLE_HTTP_CODES.contains(statusCode) || context.getConfig().getRetriableErrorCodes().contains(statusCode));
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    @VisibleForTesting
    void handleResponse() {
        Buffer responseBody = context.getResponseStream().getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        Future<TokenUsage> tokenUsageFuture = Future.succeededFuture();
        if (context.getDeployment() instanceof Model model) {
            if (context.getResponse().getStatusCode() == HttpStatus.OK.getCode()) {
                TokenUsage tokenUsage = TokenUsageParser.parse(responseBody);
                if (tokenUsage == null) {
                    Pricing pricing = model.getPricing();
                    if (pricing == null || "token".equals(pricing.getUnit())) {
                        log.warn("Can't find token usage. Trace: {}. Span: {}. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}",
                                context.getTraceId(), context.getSpanId(),
                                context.getProject(), context.getDeployment().getName(),
                                context.getDeployment().getEndpoint(),
                                context.getUpstreamRoute().get().getEndpoint(),
                                context.getResponse().getStatusCode(),
                                context.getResponseBody().length());
                    }
                    tokenUsage = new TokenUsage();
                }
                context.setTokenUsage(tokenUsage);
                proxy.getRateLimiter().increase(context).onFailure(error -> log.warn("Failed to increase limit. Trace: {}. Span: {}",
                        context.getTraceId(), context.getSpanId(), error));
                tokenUsageFuture = Future.succeededFuture(tokenUsage);
                try {
                    BigDecimal cost = ModelCostCalculator.calculate(context);
                    tokenUsage.setCost(cost);
                    tokenUsage.setAggCost(cost);
                } catch (Throwable e) {
                    log.warn("Failed to calculate cost for model={}. Trace: {}. Span: {}",
                            context.getDeployment().getName(), context.getTraceId(), context.getSpanId(), e);
                }
            }
        } else {
            tokenUsageFuture = proxy.getTokenStatsTracker().getTokenStats(context).andThen(result -> context.setTokenUsage(result.result()));
        }

        tokenUsageFuture.onComplete(ignore -> {

            proxy.getLogStore().save(context);

            log.info("Sent response to client. Trace: {}. Span: {}. Key: {}. Deployment: {}. Endpoint: {}. Upstream: {}. Status: {}. Length: {}."
                            + " Timing: {} (body={}, connect={}, header={}, body={}). Tokens: {}",
                    context.getTraceId(), context.getSpanId(),
                    context.getProject(), context.getDeployment().getName(),
                    context.getDeployment().getEndpoint(),
                    context.getUpstreamRoute().get().getEndpoint(),
                    context.getResponse().getStatusCode(),
                    context.getResponseBody().length(),
                    context.getResponseBodyTimestamp() - context.getRequestTimestamp(),
                    context.getRequestBodyTimestamp() - context.getRequestTimestamp(),
                    context.getProxyConnectTimestamp() - context.getRequestBodyTimestamp(),
                    context.getProxyResponseTimestamp() - context.getProxyConnectTimestamp(),
                    context.getResponseBodyTimestamp() - context.getProxyResponseTimestamp(),
                    context.getTokenUsage() == null ? "n/a" : context.getTokenUsage());

            finalizeRequest();
        });
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        log.warn("Failed to receive client body. Trace: {}. Span: {}. Error: {}",
                context.getTraceId(), context.getSpanId(), error.getMessage());

        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin. Trace: {}. Span: {}. Key: {}. Deployment: {}. Address: {}. Error: {}",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                buildUri(context), error.getMessage());

        respond(HttpStatus.BAD_GATEWAY, "Failed to connect to origin");
    }

    /**
     * Called when proxy failed to receive response header from origin.
     */
    private void handleProxyResponseError(Throwable error) {
        log.warn("Proxy failed to receive response header from origin. Trace: {}. Span: {}. Key: {}. Deployment: {}. Address: {}. Error:",
                context.getTraceId(), context.getSpanId(),
                context.getProject(), context.getDeployment().getName(),
                context.getProxyRequest().connection().remoteAddress(),
                error);

        context.getUpstreamRoute().retry();
        sendRequest();
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client. Trace: {}. Span: {}. Error:",
                context.getTraceId(), context.getSpanId(), error);

        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

    private static boolean isValidDeploymentApi(Deployment deployment, String deploymentApi) {
        ModelType type = switch (deploymentApi) {
            case "completions" -> ModelType.COMPLETION;
            case "chat/completions" -> ModelType.CHAT;
            case "embeddings" -> ModelType.EMBEDDING;
            default -> null;
        };

        if (type == null) {
            return false;
        }

        // Models support all APIs
        if (deployment instanceof Model model) {
            return type == model.getType();
        }

        // Assistants and applications only support chat API
        return type == ModelType.CHAT;
    }

    private static String buildUri(ProxyContext context) {
        HttpServerRequest request = context.getRequest();
        Deployment deployment = context.getDeployment();
        String endpoint = deployment.getEndpoint();
        String query = request.query();
        return endpoint + (query == null ? "" : "?" + query);
    }

    private static boolean isBaseAssistant(Deployment deployment) {
        return deployment.getName().equals(Config.ASSISTANT);
    }

    private Future<Void> respond(HttpStatus status, String errorMessage) {
        finalizeRequest();
        return context.respond(status, errorMessage);
    }

    private void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    private void respond(HttpStatus status, Object result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void finalizeRequest() {
        proxy.getTokenStatsTracker().endSpan(context);
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
}