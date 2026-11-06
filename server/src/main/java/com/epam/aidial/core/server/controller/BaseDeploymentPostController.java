package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.DeploymentInterface;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Router;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.data.RouterDecision;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.function.RouterModelFn;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.service.RouterDecisionClient;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.TokenUsageParser;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.epam.aidial.core.server.tracing.GenAiTraceAttributes;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.JsonUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.util.UpstreamInterfaceUtil;
import com.epam.aidial.core.server.util.UsagePerModelInjector;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.opentelemetry.sdk.trace.IdGenerator;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;
import static com.epam.aidial.core.server.Proxy.HEADER_USER_INFO;

@Slf4j
@AllArgsConstructor
public class BaseDeploymentPostController {
    private static final Set<Integer> DEFAULT_RETRIABLE_HTTP_CODES = Set.of(HttpStatus.TOO_MANY_REQUESTS.getCode(),
            HttpStatus.BAD_GATEWAY.getCode(), HttpStatus.GATEWAY_TIMEOUT.getCode(),
            HttpStatus.SERVICE_UNAVAILABLE.getCode());
    private static final IdGenerator SPAN_IDS = IdGenerator.random();

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

    /**
     * Publishes the upstream attempt count to the client as {@code X-UPSTREAM-ATTEMPTS}, and onto Core's own
     * span when span enrichment is on.
     */
    protected void putUpstreamAttempts(HttpServerResponse response, int attemptCount) {
        response.putHeader(Proxy.HEADER_UPSTREAM_ATTEMPTS, Integer.toString(attemptCount));
        GenAiTraceAttributes.setUpstreamAttempts(context, attemptCount);
    }

    protected void finalizeRequest() {
        // every terminal path of all four LLM surfaces reaches here, including the respond(...) helpers above
        GenAiTraceAttributes.setLatencyAttributes(context);
        // the router row reads the span's stats, which a root's endSpan deletes
        writeRouterRow()
                .onFailure(error -> log.warn("Router row is not written", error))
                .onComplete(ignore -> proxy.getTokenStatsTracker().endSpan(context)
                        .onFailure(error -> log.error("Error occurred at completing span", error)));
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
     * The router row of a decided request, written once by the pass that asked the router. Its cost is what this
     * pass's span rolled up: the deployment that served and everything below it, calls made while deciding included.
     */
    private Future<Void> writeRouterRow() {
        RouterDecision decision = context.getDecision();
        if (decision == null || !decision.takeRow()) {
            return Future.succeededFuture();
        }
        // the stats the serving report returned, read before the response ended; a pass whose root may end first
        // must not read them after it
        TokenStatsTracker.UsageStats reported = context.getSpanUsageStats();
        Future<TokenStatsTracker.UsageStats> stats = reported != null
                ? Future.succeededFuture(reported) : proxy.getTokenStatsTracker().getUsageStats(context);
        return stats
                .otherwise(error -> {
                    log.warn("Router {} row is written without its cost", decision.getRouter(), error);
                    return TokenStatsTracker.UsageStats.EMPTY;
                })
                .map(usage -> {
                    AnalyticsLogContext serving = AnalyticsLogContext.from(context, null);
                    proxy.getLogStore().save(AnalyticsLogContext.routerRow(serving, decision.toRow(), decision.getHop(), usage));
                    return null;
                });
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    protected void handleProxyConnectionError(Throwable error) {
        String reason = context.getProxyResponse() != null ? "body" : "connect";
        if (retryWithNextCandidate(HttpStatus.BAD_GATEWAY.getCode(), reason, () -> handleProxyConnectionError(error))) {
            log.warn("Can't connect to origin, trying the next candidate. Deployment: {}. Address: {}. Error: {}",
                    context.getDeployment().getName(), context.getProxyRequestUri(), error.getMessage());
            return;
        }
        ErrorData response = new ErrorData();
        response.getError().setCode(String.valueOf(HttpStatus.BAD_GATEWAY));
        String errorMessage = "Failed to connect to upstream server";
        response.getError().setMessage(errorMessage);
        response.getError().setDisplayMessage(errorMessage);
        respond(HttpStatus.BAD_GATEWAY, response);
        log.warn("Can't connect to origin.  Deployment: {}. Address: {}. Error: {}",
                context.getDeployment().getName(),
                context.getProxyRequestUri(), error.getMessage());
    }

    protected void handleRequestBodyError(Throwable error) {
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
        log.warn("Failed to receive client body. Error: {}", error.getMessage());
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    protected void handleResponseError(Throwable error, BufferingReadStream responseStream) {
        RouterDecision decision = context.getDecision();
        // the client is still there, so the upstream broke: fall back while the client saw nothing, else tell the router
        if (decision != null && !context.getResponse().closed()) {
            if (!context.getResponse().headWritten()) {
                if (retryWithNextCandidate(HttpStatus.BAD_GATEWAY.getCode(), "stream",
                        () -> respond(HttpStatus.BAD_GATEWAY, "The upstream answer broke"))) {
                    log.warn("Answer of {} broke before reaching the client, trying the next candidate: {}",
                            context.getDeployment().getName(), error.getMessage());
                    return;
                }
            } else if (!decision.isClosed()) {
                noticeRouter();
            }
        }
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
                    .onSuccess(ignored -> proxy.getLogStore().save(AnalyticsLogContext.from(context, null)))
                    .onComplete(ignored -> finalizeRequest());
        } else {
            // drop connection to stop application responding
            context.getProxyRequest().reset();
            // the pass keeps its key and span to their TTLs, but the router row is written
            writeRouterRow().onFailure(e -> log.warn("Router row is not written", e));
        }
    }

    /**
     * The chosen deployment's answer broke after bytes of it reached the client: it cannot be replaced, so the router
     * only learns of it.
     */
    private void noticeRouter() {
        RouterDecision decision = context.getDecision();
        HttpClientResponse answered = context.getProxyResponse();
        List<RouterDecision.Attempt> attempts = new ArrayList<>(decision.getAttempts());
        attempts.add(new RouterDecision.Attempt(decision.current(), answered == null ? HttpStatus.BAD_GATEWAY.getCode() : answered.statusCode(),
                "stream", true, true));
        RouterDecisionClient.notice(context, decision.getRouterConfig(), decision.getEntry(), decision.getApi(), attempts);
    }

    protected Future<Void> collectTokenUsage(Buffer responseBody) {
        return collectTokenUsage(responseBody, null);
    }

    /**
     * @param responseId DIAL's own response id when the caller knows it, null to publish none.
     */
    protected Future<Void> collectTokenUsage(Buffer responseBody, String responseId) {
        if (GenAiTraceAttributes.isEnabled(context)) {
            try {
                // interfaceType() reads the request path, which not every deployment kind reaching here has,
                // and this runs before the client response is completed - tracing must not fail the request
                GenAiTraceAttributes.setResponseAttributes(context, interfaceType(), responseId);
            } catch (Throwable e) {
                log.warn("Failed to set GenAI response trace attributes", e);
            }
        }
        if (context.getDeployment() instanceof Model model) {
            if (context.getResponse().getStatusCode() != HttpStatus.OK.getCode()) {
                return Future.succeededFuture();
            }
            TokenUsage tokenUsage = parseTokenUsage(responseBody);
            if (tokenUsage == null) {
                Pricing pricing = model.getPricing();
                if (pricing == null || "token".equals(pricing.getUnit())) {
                    Upstream currentUpstream = context.getUpstreamRoute().get();
                    log.warn("Can't find token usage. Deployment: {}. Endpoint: {}. Upstream: {}. Length: {}. Upstream.extraData: {}",
                            context.getDeployment().getName(),
                            context.getProxyRequestUri(),
                            currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                            context.getResponseBody().length(),
                            currentUpstream == null ? "N/A" : currentUpstream.getExtraData());
                }
                tokenUsage = new TokenUsage();
            }
            context.setTokenUsage(tokenUsage);
            GenAiTraceAttributes.setUsageAttributes(context, tokenUsage);
            TokenUsage usage = context.getTokenUsage();
            return increaseLimits(usage)
                    .transform(result -> {
                        if (result.failed()) {
                            log.warn("Failed to increase limit", result.cause());
                        }
                        String traceId = context.getTraceId();
                        String spanId = context.getSpanId();
                        if (traceId != null && spanId != null) {
                            return trackDeploymentStats(model.getName(), usage, false);
                        }
                        return Future.succeededFuture();
                    });
        }

        // Application/Assistant: any deployment may self-report usage in its own response body;
        // capture it alongside whatever its descendant Model spans already reported.
        TokenUsage ownUsage = parseTokenUsage(responseBody);
        return trackDeploymentStats(context.getDeployment().getName(), ownUsage, true);
    }

    protected boolean isSubjectToLimits(Deployment deployment) {
        // a router has no limits of its own: the deployments it calls back through Core are checked and charged
        return !(deployment instanceof Router) && DeploymentEndpointUtil.resolveMode(deployment, interfaceType()).isSubjectToLimits();
    }

    /**
     * Checks the initiator's limits before the request is forwarded. A translated request is not one the
     * initiator is accountable for: the call the translator makes back to Core is, so a caller over quota
     * is rejected on that one rather than on this.
     */
    protected Future<RateLimitResult> checkLimits(Deployment deployment) {
        if (!isSubjectToLimits(deployment)) {
            return Future.succeededFuture(RateLimitResult.SUCCESS);
        }
        return proxy.getRateLimiter().limit(context, deployment);
    }

    /**
     * Charges the request's usage to the initiator's token and cost limits. Skipped on the same terms
     * {@link #checkLimits} is, so the two can never disagree about what a mode exempts.
     */
    private Future<Void> increaseLimits(TokenUsage usage) {
        Deployment deployment = context.getDeployment();
        if (!isSubjectToLimits(deployment)) {
            return Future.succeededFuture();
        }
        return proxy.getRateLimiter().increase(
                deployment, BucketBuilder.buildInitiatorBucket(context), usage,
                context.getRequestBody(), context.getResponseBody(), interfaceType(), context.getPricingUsageNode(),
                context.getBudgetPayer()
        );
    }

    /**
     * Updates (or, if there's nothing new to report, just reads) the trace-wide token stats for the
     * current span, then applies the result to the context: {@link ProxyContext#getUsagePerModel()}
     * always, and - for non-Model deployments only, see {@code updateTokenUsage} - the log-facing
     * {@link ProxyContext#getTokenUsage()} too, so the analytics log stops conflating a deployment's
     * own usage with its subtree's aggregate (see GfLogStore).
     */
    private Future<Void> trackDeploymentStats(String deploymentName, TokenUsage ownUsage, boolean updateTokenUsage) {
        boolean hasOwnUsage = ownUsage != null && !ownUsage.isEmpty();

        Future<TokenStatsTracker.UsageStats> statsFuture;
        if (hasOwnUsage) {
            // a child can report after its root ended, e.g. when the client stops the root behind an interceptor
            statsFuture = proxy.getTokenStatsTracker().updateDeploymentStats(
                    context.getTraceId(), context.getSpanId(), deploymentName, ownUsage, !context.isOriginalRequest());
        } else {
            statsFuture = proxy.getTokenStatsTracker().getUsageStats(context);
        }

        return statsFuture.transform(result -> {
            if (result.failed()) {
                log.warn("Failed to track token usage", result.cause());
                return Future.succeededFuture();
            }
            TokenStatsTracker.UsageStats stats = result.result();
            context.setUsagePerModel(stats.usagePerModel());
            if (context.getDecision() != null) {
                context.setSpanUsageStats(stats);
            }
            if (updateTokenUsage) {
                TokenUsage forLog = hasOwnUsage ? ownUsage : new TokenUsage();
                if (stats.total() != null) {
                    forLog.setAggCost(stats.total().getAggCost());
                }
                context.setTokenUsage(forLog);
                GenAiTraceAttributes.setUsageAttributes(context, forLog);
            }
            return recordAggregatedCosts(stats.aggregatedCosts());
        });
    }

    /**
     * Persists each ancestor's aggregated-cost increment collected by this report - deployment-kind-agnostic,
     * on its own ledger apart from the caller's global spend and from any deployment's own direct-cost record.
     * Never fails the request: a write failure here is logged and swallowed, same as {@link #increaseLimits}.
     */
    private Future<Void> recordAggregatedCosts(List<TokenStatsTracker.AggregatedCost> aggregatedCosts) {
        if (aggregatedCosts.isEmpty()) {
            return Future.succeededFuture();
        }
        String bucket = BucketBuilder.buildInitiatorBucket(context);
        List<Future<Void>> futures = new ArrayList<>(aggregatedCosts.size());
        for (TokenStatsTracker.AggregatedCost cost : aggregatedCosts) {
            futures.add(proxy.getRateLimiter().recordAggregatedCost(cost.deploymentName(), bucket, cost.cost()));
        }
        return Future.all(futures).<Void>transform(result -> {
            if (result.failed()) {
                log.warn("Failed to record aggregated cost", result.cause());
            }
            return Future.succeededFuture();
        });
    }

    /**
     * Parses token usage from the fully buffered response body. Overridable so provider-specific
     * controllers can supply their own accounting (e.g. the Anthropic Messages API).
     */
    protected TokenUsage parseTokenUsage(Buffer responseBody) {
        return TokenUsageParser.parse(responseBody);
    }

    /**
     * Which upstream interface shape this controller's response is in, for pricing decision-tree
     * evaluation. Overridable so provider-specific controllers (Anthropic Messages, OpenAI Responses)
     * can report their own shape; the default covers the OpenAI Chat Completions path. A controller
     * that serves more than one shape from the same class (e.g. {@code DeploymentPostController}
     * also handling {@code /embeddings}) must override this to report the actual per-request shape -
     * otherwise a non-chat-completions response silently gets evaluated against the wrong alias table.
     */
    protected InterfaceType interfaceType() {
        return InterfaceType.OPENAI_CHAT_COMPLETIONS;
    }

    /**
     * Rewrites {@code body}'s {@code statistics.usage_per_model} to Core's own value, or strips it
     * entirely when Core has nothing to report - a deployment's own response is never trusted to
     * carry this field through untouched, the same guarantee {@code StripUsagePerModelFn} gives the
     * streaming path (see DeploymentPostController). Returns {@code body} unchanged only on a parse
     * surprise - injection is best-effort, never a reason to corrupt or drop a response.
     */
    protected Buffer maybeInjectUsagePerModel(Buffer body) {
        JsonNode tree = JsonUtil.tryParse(body.getBytes());
        if (!tree.isObject() || !(tree instanceof ObjectNode object)) {
            return body;
        }
        if (context.getDecision() != null) {
            RouterModelFn.rewrite(object, context.getDecision().getRouter());
        }
        List<UsagePerModel> usagePerModel = context.getUsagePerModel();
        if (usagePerModel == null || usagePerModel.isEmpty()) {
            UsagePerModelInjector.strip(object);
        } else {
            UsagePerModelInjector.inject(object, usagePerModel);
        }
        return Buffer.buffer(ProxyUtil.convertToString(object));
    }

    /**
     * Low-level: build and issue the proxy request to an already-resolved absolute URI.
     */
    protected Future<HttpClientRequest> createProxyRequest(String absoluteUri) {
        HttpServerRequest request = context.getRequest();
        context.setProxyRequestUri(absoluteUri);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(absoluteUri)
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation())
                .setConnectTimeout(proxy.getClientOptions().getConnectTimeout())
                .setIdleTimeout(proxy.getClientOptions().getIdleTimeout());

        return proxy.getClient().request(options);
    }

    protected Future<HttpClientRequest> createProxyRequest(InterfaceType type) {
        HttpServerRequest request = context.getRequest();
        return createProxyRequest(
                DeploymentEndpointUtil.resolveRequestUri(
                        context.getDeployment(),
                        type, context.getConfig().getTranslators(),
                        request.path(),
                        request.query()
                )
        );
    }

    protected Future<HttpClientResponse> sendProxyRequest(HttpClientRequest proxyRequest, InterfaceType type) {
        log.info("Connected to origin. Deployment: {}. Address: {}",
                context.getDeployment().getName(),
                proxyRequest.connection().remoteAddress());

        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!context.getDeployment().isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }
        excludeHeaders.add(HEADER_APPLICATION_PROPERTIES, "whatever");
        excludeHeaders.add(HEADER_APPLICATION_ID, "whatever");
        excludeHeaders.add(HEADER_USER_INFO, "whatever");

        ProxyUtil.copyHeaders(context.getRequest().headers(), proxyRequest.headers(), excludeHeaders);
        ProxyUtil.setOverrideNameHeader(proxyRequest, context.getDeployment());

        proxyRequest.headers().add(Proxy.HEADER_API_KEY, context.getProxyApiKeyData().getPerRequestKey());

        proxyRequest.putHeader(Proxy.HEADER_DEPLOYMENT_FEATURES,
                ProxyUtil.convertToString(FeaturesData.createDeploymentFeatures(context.getDeployment(), type)));

        if (context.getDeployment() instanceof Model model && !model.getUpstreams().isEmpty()) {
            Upstream upstream = Objects.requireNonNull(context.getUpstreamRoute().get());
            proxyRequest.putHeader(Proxy.HEADER_UPSTREAM_ENDPOINT, UpstreamInterfaceUtil.resolveEndpoint(upstream, type))
                    .putHeader(Proxy.HEADER_UPSTREAM_KEY, UpstreamInterfaceUtil.resolveKey(upstream, type))
                    .putHeader(Proxy.HEADER_UPSTREAM_EXTRA_DATA, UpstreamExtraDataMerger.merge(upstream, type))
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

        if (context.getDeployment() instanceof Router) {
            // a translator calls Core back on the router's own interface, and that pass carries the properties
            if (context.getRouterPropertiesHeader() != null
                    && DeploymentEndpointUtil.resolveMode(context.getDeployment(), type) != InterfaceMode.TRANSLATOR) {
                proxyRequest.putHeader(HEADER_APPLICATION_PROPERTIES, context.getRouterPropertiesHeader());
            }
        }

        // a translator has to know which deployment to call Core back for, and by the time it reads the body
        // the model may already have been rewritten to overrideName. MessagesBaseController carries the id
        // the client itself wrote and overrides this below; every other interface has only the deployment.
        if (DeploymentEndpointUtil.resolveMode(context.getDeployment(), type) == InterfaceMode.TRANSLATOR) {
            proxyRequest.putHeader(Proxy.HEADER_DEPLOYMENT_ID, context.getDeployment().getName());
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
            log.warn("No route. Deployment: {}", context.getDeployment().getName());
            if (!retryWithNextCandidate(e.getStatus().getCode(), "upstream", () -> respond(e))) {
                respond(e);
            }
        }

        return false;
    }

    /**
     * Asks the router for a decision when the context's deployment is a router serving {@code type} in decision
     * mode, and stores it on the context. True when it decided: the caller then serves
     * {@code context.getDecision().current()} through its own resolve, as if the client had called it.
     *
     * @param body the client's body bytes, read only when the entry asks for them
     */
    protected Future<Boolean> decideIfRouter(InterfaceType type, Future<Buffer> body) {
        if (!(context.getDeployment() instanceof Router router)) {
            return Future.succeededFuture(false);
        }
        try {
            context.setRouterPropertiesHeader(proxy.getApplicationSchemaService().routerPropertiesHeader(router));
        } catch (HttpException e) {
            return Future.failedFuture(e);
        }
        if (DeploymentEndpointUtil.resolveMode(router, type) != InterfaceMode.DECISION) {
            return Future.succeededFuture(false);
        }
        // only a pass below this router's own decision has it in its path: a deployment it chose calls it back
        List<String> path = context.getApiKeyData().getExecutionPath();
        if (path != null && path.contains(router.getName())) {
            return Future.failedFuture(new HttpException(HttpStatus.BAD_GATEWAY, "Router %s re-entered".formatted(router.getName())));
        }
        DeploymentInterface entry = router.getInterfaces().get(type.getValue());
        boolean withBody = entry.getContext() != null && Boolean.TRUE.equals(entry.getContext().getBody());
        // the span first: calls the router makes with the decide key roll up into it, and the candidate's pass re-enters it
        return (withBody ? body : Future.<Buffer>succeededFuture(null))
                .compose(bytes -> proxy.getTokenStatsTracker().startSpan(context)
                        .compose(ignore -> proxy.getTaskExecutor().submit(() -> mintDecideKey(entry)))
                        .compose(key -> decide(router, entry, type, bytes, key)));
    }

    /**
     * The key the router may call Core with while it decides: the caller's identity and rights, the router last in
     * its path. It lives only as long as the decide call, so it is not the context's proxy key.
     */
    private ApiKeyData mintDecideKey(DeploymentInterface entry) {
        ApiKeyData key = new ApiKeyData();
        ApiKeyData.initFromContext(key, context);
        proxy.getApiKeyStore().assignPerRequestApiKey(key, RouterDecisionClient.keyTtl(entry));
        return key;
    }

    private Future<Boolean> decide(Router router, DeploymentInterface entry, InterfaceType type, Buffer bytes, ApiKeyData key) {
        long start = System.currentTimeMillis();
        return askRouter(router, entry, type, bytes, key, List.of())
                .map(candidates -> {
                    RouterDecision decision = new RouterDecision(router.getName(), type, candidates, System.currentTimeMillis() - start);
                    decision.setRouterConfig(router);
                    decision.setEntry(entry);
                    decision.setKeyTemplate(key);
                    decision.setHop(routerHop(router));
                    context.setDecision(decision);
                    return true;
                });
    }

    /**
     * The router row's place: a span of its own under the request's parent, and the parent and path a direct call to
     * the router has. The incoming key's path, as the key minted for this pass does not exist yet.
     */
    private RouterDecision.Hop routerHop(Router router) {
        List<String> incoming = context.getApiKeyData().getExecutionPath();
        List<String> path = new ArrayList<>(incoming == null ? List.of() : incoming);
        path.add(router.getName());
        String parent = AnalyticsLogContext.getParentDeployment(context.getSourceDeployment(), context.getInterceptors(), path);
        return new RouterDecision.Hop(SPAN_IDS.generateSpanId(), context.getParentSpanId(), parent, List.copyOf(path));
    }

    /**
     * One decide request with its own key, invalidated when the call ends; a router named as a candidate fails it.
     */
    private Future<List<String>> askRouter(Router router, DeploymentInterface entry, InterfaceType type, Buffer bytes, ApiKeyData key,
                                           List<RouterDecision.Attempt> attempts) {
        return RouterDecisionClient.decide(context, router, entry, type, bytes, key.getPerRequestKey(), attempts)
                .onComplete(ignore -> proxy.getApiKeyStore().invalidatePerRequestApiKey(key)
                        .onFailure(error -> log.error("error occurred on invalidating the decide request key", error)))
                .map(candidates -> {
                    for (String candidate : candidates) {
                        if (context.getConfig().getRouters().containsKey(candidate)) {
                            throw new HttpException(HttpStatus.BAD_GATEWAY, "A router cannot be a routing candidate: " + candidate);
                        }
                    }
                    return candidates;
                });
    }

    /**
     * Every candidate so far failed: asks the router again with the attempts, and re-enters with the new verdict's
     * first candidate. When no new verdict can be had (the router fails, answers no usable verdict, or the client
     * left), marks the decision exhausted and runs {@code whenExhausted}, which answers the last failure.
     */
    private void redecide(Runnable whenExhausted) {
        RouterDecision decision = context.getDecision();
        DeploymentInterface entry = decision.getEntry();
        boolean withBody = entry.getContext() != null && Boolean.TRUE.equals(entry.getContext().getBody());
        Buffer bytes = withBody ? context.getOriginalRequestBody() : null;
        long start = System.currentTimeMillis();
        proxy.getTokenStatsTracker().unnameSpan(context)
                .recover(error -> {
                    log.warn("Router {} decides again in a span named after the failed candidate", decision.getRouter(), error);
                    return Future.succeededFuture();
                })
                .compose(ignore -> proxy.getTaskExecutor().submit(() -> mintKeyLike(decision.getKeyTemplate(), entry)))
                .compose(key -> askRouter(decision.getRouterConfig(), entry, decision.getApi(), bytes, key, decision.getAttempts()))
                .onComplete(result -> {
                    if (result.succeeded() && !context.getResponse().closed()) {
                        decision.redecided(result.result(), System.currentTimeMillis() - start);
                        reenterWithCandidate();
                        return;
                    }
                    if (result.failed()) {
                        log.warn("Router {} failed to decide again: {}", decision.getRouter(), result.cause().getMessage());
                    }
                    decision.exhaust();
                    whenExhausted.run();
                });
    }

    /**
     * A new decide request key with the first one's identity, path and grants: the context now names the failed
     * candidate, so building the key from it again would put the candidate after the router.
     */
    private ApiKeyData mintKeyLike(ApiKeyData template, DeploymentInterface entry) {
        ApiKeyData key = ProxyUtil.convertToObject(ProxyUtil.convertToString(template), ApiKeyData.class);
        key.setPerRequestKey(null);
        proxy.getApiKeyStore().assignPerRequestApiKey(key, RouterDecisionClient.keyTtl(entry));
        return key;
    }

    private boolean canRedecide(RouterDecision decision) {
        return decision.canRedecide(RouterDecisionClient.maxDecisions(decision.getEntry()));
    }

    @SneakyThrows
    protected void writeRequestBody(RequestObject request) {
        context.setRequestBody(Buffer.buffer(request.serialize()));
    }

    /**
     * Serves the decision's current candidate from the start of this controller's flow, the resolve included.
     * Only the controllers that run {@link #decideIfRouter} have a decision to re-enter with.
     */
    protected void reenterWithCandidate() {
        throw new IllegalStateException("No decision to re-enter with in " + getClass().getSimpleName());
    }

    /**
     * The deployment's interceptors for this pass. In the pass an interceptor chain hands to a router, the chosen
     * deployment skips the interceptors that chain already ran, as a direct call runs them once.
     */
    protected List<String> resolveInterceptors(Deployment deployment) {
        List<String> interceptors = proxy.getDeploymentService().getInterceptors(context, deployment);
        if (context.getDecision() != null && context.getApiKeyData().isInterceptor()) {
            interceptors.removeAll(context.getApiKeyData().getInterceptors());
        }
        return interceptors;
    }

    /**
     * A refusal of the candidate being resolved, before anything was sent to it, skips to the next candidate, or
     * after the last one asks the router again: answers null then, and {@code answer} gets the error if no new
     * verdict can be had. Otherwise answers the error to respond with; an unknown last candidate is the router's
     * fault, not the caller's, and is answered 502.
     */
    protected Throwable nextCandidateOrError(Throwable error, Consumer<Throwable> answer) {
        RouterDecision decision = context.getDecision();
        if (decision == null || !decision.isResolving()) {
            return error;
        }
        int status;
        String reason;
        if (error instanceof PermissionDeniedException) {
            status = HttpStatus.FORBIDDEN.getCode();
            reason = "forbidden";
        } else if (error instanceof ResourceNotFoundException) {
            status = HttpStatus.NOT_FOUND.getCode();
            reason = "not_found";
        } else if (error instanceof HttpException httpException) {
            status = httpException.getStatus().getCode();
            reason = status == HttpStatus.TOO_MANY_REQUESTS.getCode() ? "limit" : "unavailable";
        } else {
            return error;
        }
        if (!context.getResponse().closed()) {
            if (decision.hasNext()) {
                log.warn("Candidate {} of router {} skipped: {}", decision.current(), decision.getRouter(), error.getMessage());
                decision.skip(status, reason);
                reenterWithCandidate();
                return null;
            }
            if (canRedecide(decision)) {
                log.warn("Candidate {} of router {} skipped, deciding again: {}", decision.current(), decision.getRouter(), error.getMessage());
                decision.fail(status, reason, false);
                redecide(() -> answer.accept(error));
                return null;
            }
        }
        return error instanceof ResourceNotFoundException
                ? new HttpException(HttpStatus.BAD_GATEWAY, "Router %s chose an unknown deployment".formatted(decision.getRouter()))
                : error;
    }

    /**
     * After the current candidate's call failed (a connect failure, a retriable status once its own upstreams are
     * exhausted, or a 200 answer that broke before the whole of it arrived), re-enters with the next candidate, or
     * after the last one asks the router again, while no byte has reached the client and the client is still there.
     * True when it did; {@code answerOriginal} then answers the failure if no new verdict can be had.
     */
    protected boolean retryWithNextCandidate(int status, String reason, Runnable answerOriginal) {
        RouterDecision decision = context.getDecision();
        HttpServerResponse response = context.getResponse();
        if (decision == null || response.headWritten() || response.closed()) {
            return false;
        }
        HttpClientResponse answered = context.getProxyResponse();
        // an answer counts as broken only when its 200 body never fully arrived; a background job's accepted job,
        // or a response mapping already saved, would keep running beside the next candidate's
        if (answered != null && (answered.statusCode() != HttpStatus.OK.getCode()
                || context.isUpstreamBodyReceived() || context.isBackgroundJob())) {
            return false;
        }
        boolean next = decision.hasNext();
        if (!next && !canRedecide(decision)) {
            return false;
        }
        boolean received = answered != null;
        if (received) {
            // drop the half-read upstream and the headers its answer already copied to the client response
            context.getProxyRequest().reset();
            context.setProxyResponse(null);
            response.headers().clear();
            response.setChunked(false);
        }
        // the failed pass ends without answering, so its key would otherwise live until its TTL
        ApiKeyData failedKey = context.getProxyApiKeyData();
        if (failedKey != null && failedKey.getPerRequestKey() != null) {
            proxy.getApiKeyStore().invalidatePerRequestApiKey(failedKey)
                    .onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
        context.setProxyApiKeyData(null);
        context.setCacheBreakpointContext(null);
        context.setUpstreamBodyReceived(false);
        context.setSpanUsageStats(null);
        context.setRequestBody(context.getOriginalRequestBody());
        if (next) {
            decision.skip(status, reason, received);
            reenterWithCandidate();
        } else {
            decision.fail(status, reason, received);
            redecide(answerOriginal);
        }
        return true;
    }
}
