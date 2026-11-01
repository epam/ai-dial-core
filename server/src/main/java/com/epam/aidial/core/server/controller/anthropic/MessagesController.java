package com.epam.aidial.core.server.controller.anthropic;

import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.openapi.annotations.ApiHeader;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiOperations;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.ResponsesController;
import com.epam.aidial.core.server.controller.SpendCapCheck;
import com.epam.aidial.core.server.data.AnthropicErrorData;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.BuildUpstreamCacheFn;
import com.epam.aidial.core.server.function.CollectMessagesTokenUsageFn;
import com.epam.aidial.core.server.function.EncryptedContentWrapFn;
import com.epam.aidial.core.server.function.enhancement.ApplyDefaultDeploymentSettingsFn;
import com.epam.aidial.core.server.function.request.MessagesApiRequest;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.function.request.ResponsesApiRequest;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.SpendCap;
import com.epam.aidial.core.server.limiter.SpendCaps;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.token.MessagesTokenUsageParser;
import com.epam.aidial.core.server.token.TokenUsage;
import com.epam.aidial.core.server.tracing.GenAiTraceAttributes;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResponseIdUtil;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.translator.MessagesToResponses;
import com.epam.aidial.core.translator.ResponsesToMessagesStream;
import com.epam.aidial.core.translator.UntranslatableRequestException;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerResponse;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Anthropic Messages API pass-through controller. Mirrors {@link ResponsesController} but forwards
 * the body verbatim (no server-side store, no response-id rewrite) and accounts token usage with
 * Anthropic semantics (see {@link MessagesTokenUsageParser} / {@link CollectMessagesTokenUsageFn}).
 */
@Slf4j
public class MessagesController extends MessagesBaseController {

    /**
     * The translated body sent upstream when a builtin translator serves the request; the context keeps the
     * client's own body for pricing and the analytics log.
     */
    private Buffer translatedRequestBody;

    /**
     * The {@code model} the client asked for, reported back when the upstream names none.
     */
    private String requestedModel;

    /**
     * The caller's caps as read by {@link #verifyLimit()}; null when no caps were read.
     */
    @Nullable
    private SpendCaps spendCaps;

    public MessagesController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected boolean isBuiltinTranslationSupported() {
        return true;
    }

    @Override
    protected void prepareUpstreamRoute(RequestObject request) {
        if (builtinTranslator == null) {
            super.prepareUpstreamRoute(request);
        } else {
            prepareTranslatedUpstreamRoute((MessagesApiRequest) request);
        }
    }

    /**
     * The request as the Responses pass of a translator service would serve it: the Messages defaults apply
     * to what the client sent, then the body is translated and runs the {@link ResponsesController} chain,
     * which grants the per-request key the files the body refers to, unwraps replayed encrypted items onto
     * their upstream and keys the upstream cache on the Responses body the upstream answers for.
     */
    @SneakyThrows
    private void prepareTranslatedUpstreamRoute(MessagesApiRequest request) {
        if (!request.isStreaming()) {
            throw new HttpException(HttpStatus.BAD_REQUEST, "The builtin translator does not serve non-streaming requests yet");
        }
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);

        ProxyUtil.processChain(request, List.of(new ApplyDefaultDeploymentSettingsFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES)));
        ResponsesApiRequest translated;
        try {
            translated = new ResponsesApiRequest(MessagesToResponses.translateRequest(request.getTree()));
        } catch (UntranslatableRequestException e) {
            throw new HttpException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
        ProxyUtil.processChain(translated, ResponsesController.buildEnhancementFunctions(proxy, context));
        // Enhancement functions update the api key, and it should be saved after that
        proxy.getApiKeyStore().assignPerRequestApiKey(proxyApiKeyData);

        requestedModel = request.getModel();
        context.setRequestBody(Buffer.buffer(request.serialize()));
        translatedRequestBody = Buffer.buffer(translated.serialize());

        String upstreamId = context.getRequest().headers().get(Proxy.HEADER_UPSTREAM_ID);
        if (upstreamId == null) {
            upstreamId = translated.getEncryptedUpstreamId();
        }
        UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider()
                .get(context.getDeployment(), context.getCacheBreakpointContext(),
                        dep -> DeploymentEndpointUtil.resolveServingEndpoint(dep, builtinTranslator.getOut(),
                                context.getConfig().getTranslators()), upstreamId);
        context.setRequestBodyTimestamp(System.currentTimeMillis());
        context.setUpstreamRoute(upstreamRoute);
    }

    @Override
    protected Buffer resolveProxyRequestBody() {
        return builtinTranslator == null ? super.resolveProxyRequestBody() : translatedRequestBody;
    }

    /**
     * Appends {@link BuildUpstreamCacheFn} before the base chain's terminal {@code CollectDeploymentsFn},
     * so upstream cache pinning applies to {@code POST /anthropic/v1/messages} but not to
     * {@link MessagesCountTokensController}, which inherits the base chain unchanged.
     */
    @Override
    protected List<BaseRequestFunction<RequestObject>> buildEnhancementFunctions() {
        List<BaseRequestFunction<RequestObject>> functions = new ArrayList<>(super.buildEnhancementFunctions());
        functions.add(functions.size() - 1, new BuildUpstreamCacheFn(proxy, context, InterfaceType.ANTHROPIC_MESSAGES));
        return functions;
    }

    /**
     * Adds GenAI request attributes here rather than in the base parser, so that
     * {@link MessagesCountTokensController} - which generates nothing - publishes none.
     */
    @Override
    protected MessagesApiRequest parseBody(Buffer body) {
        MessagesApiRequest request = super.parseBody(body);
        GenAiTraceAttributes.setRequestAttributes(context, InterfaceType.ANTHROPIC_MESSAGES, request.getTree());
        return request;
    }

    @ApiOperations({
            @ApiOperation(
                    method = "POST",
                    path = "/anthropic/v1/messages",
                    operationId = "createAnthropicMessage",
                    requestBody = @ApiSchema(schemaRef = "ProxyRequest"),
                    tags = {"Anthropic"},
                    parameters = {
                            @ApiParameter(name = "anthropic-version", in = ParameterIn.HEADER, required = true,
                                    description = "The Anthropic API version (e.g., 2023-06-01)"),
                            @ApiParameter(name = Proxy.HEADER_CACHE_POLICY, in = ParameterIn.HEADER,
                                    description = OpenApiDescriptions.CACHE_POLICY,
                                    allowableValues = {"availability-priority", "cache-priority"})
                    },
                    responses = {
                            @ApiResponse(code = 200, description = OpenApiDescriptions.RESPONSE_SUCCESS,
                                    body = @ApiSchema(schemaRef = "ProxyResponse"),
                                    headers = {
                                            @ApiHeader(name = Proxy.HEADER_UPSTREAM_ATTEMPTS,
                                                    description = "Number of upstream attempts performed before returning the response"),
                                            @ApiHeader(name = "anthropic-ratelimit-unified-status",
                                                    description = "DIAL spend cap status before this request: `allowed`, or `allowed_warning` past 75%. "
                                                            + "Sent only when the caller has a day/week/month cost limit; "
                                                            + "the upstream's own `anthropic-ratelimit-*` headers are removed"),
                                            @ApiHeader(name = "anthropic-ratelimit-unified-representative-claim", description = "Always `overage`"),
                                            @ApiHeader(name = "anthropic-ratelimit-unified-overage-status", description = "Same value as `anthropic-ratelimit-unified-status`"),
                                            @ApiHeader(name = "anthropic-ratelimit-unified-overage-utilization",
                                                    description = "Used/total of the reported spend cap, floored to two decimals and capped at 0.99"),
                                            @ApiHeader(name = "anthropic-ratelimit-unified-overage-surpassed-threshold",
                                                    description = "`0.75` or `0.95` once the utilization reaches it; absent below 75%"),
                                            @ApiHeader(name = "anthropic-ratelimit-unified-reset", description = "When the reported spend cap resets, Unix seconds"),
                                            @ApiHeader(name = "anthropic-ratelimit-unified-overage-reset", description = "Same value as `anthropic-ratelimit-unified-reset`")
                                    }),
                            @ApiResponse(code = 400),
                            @ApiResponse(code = 403),
                            @ApiResponse(code = 404),
                            @ApiResponse(code = 415),
                            @ApiResponse(code = 429, description = "DIAL limit reached, with an Anthropic-format error body "
                                    + "(`{\"type\":\"error\",\"error\":{\"type\":...,\"message\":...}}`). A reached spend cap gives `billing_error`, "
                                    + "`x-should-retry: false` and `anthropic-ratelimit-unified-*` headers describing the cap; "
                                    + "an unreadable spend counter of a caller with a cost limit gives `billing_error` with only `x-should-retry: false` and "
                                    + "`anthropic-ratelimit-unified-overage-disabled-reason: fetch_error`; "
                                    + "a token, request or per-minute cost limit gives `rate_limit_error` with `retry-after`, and `x-should-retry: true` for rolling windows only",
                                    body = @ApiSchema(implementation = AnthropicErrorData.class)),
                            @ApiResponse(code = 500),
                            @ApiResponse(code = 502, description = "Bad Gateway - failed to connect to upstream server", body = @ApiSchema(implementation = ErrorData.class)),
                            @ApiResponse(code = 503)
                    }
            )
    })
    @Override
    public Future<?> handle() {
        return super.handle();
    }

    @Override
    protected Future<Void> verifyLimit() {
        String deploymentName = context.getDeployment().getName();
        return SpendCapCheck.verify(proxy, context, isSubjectToLimits(context.getDeployment()), new SpendCapCheck.Answers() {
            @Override
            public HttpException spendCapReached(SpendCap cap, @Nullable String displayMessage, long nowMillis) {
                return AnthropicLimitResponses.spendCapReached(cap, nowMillis);
            }

            @Override
            public HttpException spendUnavailable() {
                return AnthropicLimitResponses.spendUnavailable();
            }

            @Override
            public Throwable limitCheckFailed(Throwable error) {
                // any failure other than an unreadable spend record is a server error Claude Code retries
                return AnthropicLimitResponses.limitCheckFailed();
            }

            @Override
            public void rejectOtherLimit(RateLimitResult result) {
                if (result.status() == HttpStatus.TOO_MANY_REQUESTS && result.breach() != null) {
                    throw AnthropicLimitResponses.limitReached(result.breach(), deploymentName, System.currentTimeMillis());
                }
                result.throwIfError();
            }
        }).map(caps -> {
            spendCaps = caps;
            return null;
        });
    }

    /**
     * Must run before the first body write. Spend headers go on a 200 only and carry the state read before the request.
     */
    private void putLimitHeaders(HttpServerResponse response, HttpClientResponse proxyResponse) {
        AnthropicLimitResponses.stripUpstreamRateLimitHeaders(response.headers());
        if (proxyResponse.statusCode() == 200 && spendCaps != null && spendCaps.reported() != null) {
            AnthropicLimitResponses.putSpendHeaders(response.headers(), spendCaps.reported());
        }
    }

    @Override
    protected void processResponse(HttpClientResponse proxyResponse) {
        if (!context.isStreamingRequest()) {
            proxyResponse.body()
                    .compose(body -> handleNonStreamingResponse(proxyResponse, body))
                    .onFailure(this::handleProxyConnectionError);
            return;
        }

        BufferingReadStream responseStream;
        if (builtinTranslator != null && proxyResponse.statusCode() == HttpStatus.OK.getCode() && isEventStreamResponse(proxyResponse)) {
            // translated before it is buffered, so the rest of the path sees the stream the client gets
            ResponsesToMessagesReadStream translated = new ResponsesToMessagesReadStream(proxyResponse,
                    new ResponsesToMessagesStream(requestedModel,
                            ResponseIdUtil.createResponseId(context.getDeployment().getName(), proxy.getGenerator().get())),
                    new EncryptedContentWrapFn(proxy, context));
            responseStream = new BufferingReadStream(translated, ProxyUtil.contentLength(proxyResponse, 1024),
                    new MessagesSseListener(List.of(new CollectMessagesTokenUsageFn(proxy, context))));
        } else {
            responseStream = createResponseStream(proxyResponse,
                    () -> new MessagesSseListener(List.of(new CollectMessagesTokenUsageFn(proxy, context))));
        }

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);
        putLimitHeaders(response, proxyResponse);
        putUpstreamAttempts(response, context.getUpstreamRoute().getAttemptCount());

        responseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(responseStream))
                .onFailure(error -> handleResponseError(error, responseStream));
    }

    private Future<Void> handleNonStreamingResponse(HttpClientResponse proxyResponse, Buffer body) {
        context.setResponseBody(body);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        HttpServerResponse response = context.getResponse();
        ProxyUtil.copyResponse(response, proxyResponse);
        putLimitHeaders(response, proxyResponse);
        response.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(body.length()));
        putUpstreamAttempts(response, context.getUpstreamRoute().getAttemptCount());
        return collectTokenUsage(body)
                .transform(result -> {
                    if (result.failed()) {
                        log.warn("Failed to collect token usage", result.cause());
                    }
                    completeProxyResponse(() -> response.end(body));
                    return Future.<Void>succeededFuture();
                });
    }

    private void handleResponse(BufferingReadStream responseStream) {
        Buffer responseBody = responseStream.getContent();
        context.setResponseBody(responseBody);
        context.setResponseBodyTimestamp(System.currentTimeMillis());
        collectTokenUsage(responseBody).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to collect token usage", result.cause());
            }
            completeProxyResponse(() -> responseStream.end(context.getResponse()));
        });
    }

    private void completeProxyResponse(Runnable endResponse) {
        endResponse.run();
        proxy.getLogStore().save(AnalyticsLogContext.from(context, null));
        Upstream currentUpstream = context.getUpstreamRoute().get();
        log.info("Sent response to client. Deployment: {}. Interface: {}. Endpoint: {}. Upstream: {}. Length: {}."
                        + " Timing: {} (body={}, connect={}, header={}, body={}). Tokens: {}. Upstream.extraData: {}",
                context.getDeployment().getName(),
                InterfaceType.ANTHROPIC_MESSAGES.getValue(),
                context.getProxyRequestUri(),
                currentUpstream == null ? "N/A" : currentUpstream.getEndpoint(),
                context.getResponseBody() == null ? 0 : context.getResponseBody().length(),
                context.getResponseBodyTimestamp() - context.getRequestTimestamp(),
                context.getRequestBodyTimestamp() - context.getRequestTimestamp(),
                context.getProxyConnectTimestamp() - context.getRequestBodyTimestamp(),
                context.getProxyResponseTimestamp() - context.getProxyConnectTimestamp(),
                context.getResponseBodyTimestamp() - context.getProxyResponseTimestamp(),
                context.getTokenUsage() == null ? "N/A" : context.getTokenUsage(),
                currentUpstream == null ? "N/A" : currentUpstream.getExtraData());

        finalizeRequest();
    }

    @Override
    protected TokenUsage parseTokenUsage(Buffer responseBody) {
        if (context.isStreamingRequest()) {
            // Populated event-by-event by CollectMessagesTokenUsageFn during streaming.
            return context.getTokenUsage();
        }
        return MessagesTokenUsageParser.parse(responseBody);
    }

    @Override
    protected InterfaceType interfaceType() {
        return InterfaceType.ANTHROPIC_MESSAGES;
    }
}
