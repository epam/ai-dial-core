package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceMode;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Pricing;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.function.CollectResponseAttachmentsFn;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.token.TokenUsageParser;
import com.epam.aidial.core.server.token.UsagePerModel;
import com.epam.aidial.core.server.tracing.GenAiTraceAttributes;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.BucketBuilder;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ModelCostCalculator;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.UpstreamExtraDataMerger;
import com.epam.aidial.core.server.util.UpstreamInterfaceUtil;
import com.epam.aidial.core.server.util.UsagePerModelInjector;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
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
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
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
            return collectResponseAttachments(tree, fn);
        } catch (Throwable e) {
            log.warn("Can't parse JSON response body. Error:", e);
            return Future.failedFuture(e);
        }
    }

    /**
     * @param responseTree the response already parsed by a caller that needed it for its own reasons
     *                     (e.g. to rewrite its id) - reused here instead of serializing it back to a
     *                     Buffer and parsing the same JSON again.
     */
    protected Future<Void> collectResponseAttachments(JsonNode responseTree, CollectResponseAttachmentsFn fn) {
        if (isEventStreamResponse(context.getProxyResponse())) {
            return Future.succeededFuture();
        }
        if (!(responseTree instanceof ObjectNode responseObject)) {
            // same outcome as the Buffer overload, whose (ObjectNode) cast fails on a non-object body
            Throwable error = new IllegalStateException("Response body is not a JSON object");
            log.warn("Can't collect attachments from response body. Error:", error);
            return Future.failedFuture(error);
        }
        return fn.apply(responseObject).map(ignored -> null);
    }

    // These respond(...) helpers are terminal: context.respond(...) ends the HTTP response and, with it, the
    // OTel span synchronously - so latency attributes must be published before that call, not inside
    // finalizeRequest(), which runs after (see finalizeRequest()'s own comment).
    protected Future<?> respond(HttpStatus status, String errorMessage) {
        GenAiTraceAttributes.setLatencyAttributes(context);
        finalizeRequest();
        return context.respond(status, errorMessage);
    }

    protected void respond(HttpException exception) {
        GenAiTraceAttributes.setLatencyAttributes(context);
        finalizeRequest();
        context.respond(exception);
    }

    protected void respond(HttpStatus status) {
        GenAiTraceAttributes.setLatencyAttributes(context);
        finalizeRequest();
        context.respond(status);
    }

    protected void respond(HttpStatus status, Object result) {
        GenAiTraceAttributes.setLatencyAttributes(context);
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
        // must run before reset(): Vert.x ends the request's OTel span synchronously inside reset(),
        // after which further span attributes (dial.latency.*) are silently dropped
        GenAiTraceAttributes.setLatencyAttributes(context);
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
        }
    }

    protected Future<Void> collectTokenUsage(Buffer responseBody) {
        return collectTokenUsage(responseBody, null);
    }

    /**
     * For a caller holding only the raw body - nothing has parsed it, so token usage comes from the byte-scan
     * and tracing derives its own view. A caller that already parsed the response uses the {@link JsonNode}
     * overload instead, so the same body is never read twice.
     *
     * @param responseId DIAL's own response id when the caller knows it, null to take the id from the body.
     */
    protected Future<Void> collectTokenUsage(Buffer responseBody, String responseId) {
        setResponseTraceAttributes(() -> {
            JsonNode responseTree = GenAiTraceAttributes.parseResponse(context, interfaceType(), responseBody);
            GenAiTraceAttributes.setResponseAttributes(context, interfaceType(), responseTree, responseId);
        });
        // legacy path: covers streaming, /embeddings and the disconnect-recovery case above, none of which
        // hold a parsed response - preserved exactly as it was, including preferring a live accumulated
        // usage node over parsing the (possibly multi-chunk SSE) buffer, which the JsonNode overload below
        // has no equivalent of and must not be made to share
        return chargeTokenUsage(() -> parseTokenUsage(responseBody), this::increaseLimits);
    }

    /**
     * For a caller that already parsed the response for its own reasons (e.g. to rewrite its id).
     *
     * @param responseId DIAL's own response id when the caller knows it, null to take the id from the response.
     */
    protected Future<Void> collectTokenUsage(JsonNode response, String responseId) {
        setResponseTraceAttributes(() ->
                GenAiTraceAttributes.setResponseAttributes(context, interfaceType(), response, responseId));
        return chargeTokenUsage(() -> parseTokenUsage(response), usage -> increaseLimits(usage, response));
    }

    private void setResponseTraceAttributes(Runnable enrichment) {
        if (GenAiTraceAttributes.isEnabled(context)) {
            try {
                // interfaceType() reads the request path, which not every deployment kind reaching here has,
                // and this runs before the client response is completed - tracing must not fail the request
                enrichment.run();
            } catch (Throwable e) {
                log.warn("Failed to set GenAI response trace attributes", e);
            }
        }
    }

    /**
     * @param usageSupplier  reads the usage out of whichever representation the caller holds; invoked only on
     *                       the paths that charge for it, so an error response is never read for usage at all.
     * @param limitsCharger  charges token/cost limits for the resolved usage - each {@code collectTokenUsage}
     *                       overload supplies its own, so the two representations never meet in this method.
     */
    private Future<Void> chargeTokenUsage(Supplier<TokenUsage> usageSupplier, Function<TokenUsage, Future<Void>> limitsCharger) {
        if (context.getDeployment() instanceof Model model) {
            if (context.getResponse().getStatusCode() != HttpStatus.OK.getCode()) {
                return Future.succeededFuture();
            }
            TokenUsage tokenUsage = usageSupplier.get();
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
            return limitsCharger.apply(usage)
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
        return trackDeploymentStats(context.getDeployment().getName(), usageSupplier.get(), true);
    }

    private boolean subjectToLimits(Deployment deployment) {
        return DeploymentEndpointUtil.resolveMode(deployment, interfaceType()).isSubjectToLimits();
    }

    /**
     * Checks the initiator's limits before the request is forwarded. A translated request is not one the
     * initiator is accountable for: the call the translator makes back to Core is, so a caller over quota
     * is rejected on that one rather than on this.
     */
    protected Future<RateLimitResult> checkLimits(Deployment deployment) {
        if (!subjectToLimits(deployment)) {
            return Future.succeededFuture(RateLimitResult.SUCCESS);
        }
        return proxy.getRateLimiter().limit(context, deployment);
    }

    /**
     * Charges the request's usage to the initiator's token and cost limits, for a caller holding no parsed
     * response. Skipped on the same terms {@link #checkLimits} is, so the two can never disagree about what
     * a mode exempts. Unchanged by the parse-once refactor: {@link RateLimiter#increase(RoleBasedEntity,
     * String, TokenUsage, Buffer, Buffer, InterfaceType, JsonNode)} still resolves pricing itself, preferring
     * {@code context.getPricingUsageNode()} - a live per-event accumulation - over parsing {@code responseBody},
     * which for a streamed body may not even be a single JSON document.
     */
    private Future<Void> increaseLimits(TokenUsage usage) {
        Deployment deployment = context.getDeployment();
        if (!subjectToLimits(deployment)) {
            return Future.succeededFuture();
        }
        return proxy.getRateLimiter().increase(
                deployment, BucketBuilder.buildInitiatorBucket(context), usage,
                context.getRequestBody(), context.getResponseBody(), interfaceType(), context.getPricingUsageNode()
        );
    }

    /**
     * Charges the request's usage to the initiator's token and cost limits, for a caller that already parsed
     * the response. Reads only that tree for pricing - never {@code context.getResponseBody()} - so the body
     * this {@code response} was parsed from is not deserialized again here.
     */
    private Future<Void> increaseLimits(TokenUsage usage, JsonNode response) {
        Deployment deployment = context.getDeployment();
        if (!subjectToLimits(deployment)) {
            return Future.succeededFuture();
        }
        BigDecimal cost = ModelCostCalculator.resolveCost(deployment, usage, context.getRequestBody(), interfaceType(),
                new ModelCostCalculator.ResponseSource.Tree(response));
        return proxy.getRateLimiter().increase(deployment, BucketBuilder.buildInitiatorBucket(context), usage, cost);
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
            statsFuture = proxy.getTokenStatsTracker().updateDeploymentStats(
                    context.getTraceId(), context.getSpanId(), deploymentName, ownUsage);
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
            if (updateTokenUsage) {
                TokenUsage forLog = hasOwnUsage ? ownUsage : new TokenUsage();
                if (stats.total() != null) {
                    forLog.setAggCost(stats.total().getAggCost());
                }
                context.setTokenUsage(forLog);
                GenAiTraceAttributes.setUsageAttributes(context, forLog);
            }
            return Future.<Void>succeededFuture();
        });
    }

    /**
     * Parses token usage from the fully buffered response body, without parsing the body as a whole.
     * Overridable so provider-specific controllers can supply their own accounting (e.g. the Anthropic
     * Messages API).
     */
    protected TokenUsage parseTokenUsage(Buffer responseBody) {
        return TokenUsageParser.parse(responseBody);
    }

    /**
     * Reads token usage out of a response a caller already parsed. Overridable alongside
     * {@link #parseTokenUsage(Buffer)} - a controller that overrides one must consider the other.
     */
    protected TokenUsage parseTokenUsage(JsonNode response) {
        return TokenUsageParser.parse(response);
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
     * Rewrites {@code responseTree}'s {@code statistics.usage_per_model} to Core's own value, or strips it
     * entirely when Core has nothing to report - a deployment's own response is never trusted to
     * carry this field through untouched, the same guarantee {@code StripUsagePerModelFn} gives the
     * streaming path (see DeploymentPostController).
     *
     * @param responseObject the parsed response, mutated in place and re-serialized - the only representation
     *                        this touches; a caller with an unparsed or non-object response serializes nothing
     *                        through here at all, forwarding its own bytes unchanged instead.
     */
    protected Buffer injectUsagePerModel(ObjectNode responseObject) {
        List<UsagePerModel> usagePerModel = context.getUsagePerModel();
        if (usagePerModel == null || usagePerModel.isEmpty()) {
            UsagePerModelInjector.strip(responseObject);
        } else {
            UsagePerModelInjector.inject(responseObject, usagePerModel);
        }
        return Buffer.buffer(ProxyUtil.convertToString(responseObject));
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
            respond(e);
            log.warn("No route. Deployment: {}", context.getDeployment().getName());
        }

        return false;
    }
}
