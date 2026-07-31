package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.BaseResponseFunction;
import com.epam.aidial.core.server.function.FilterAllowedToolsFn;
import com.epam.aidial.core.server.function.RewriteMcpUiDomainFn;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.mcp.McpClientUtils;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.Compression;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.HttpConnection;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;

@Slf4j
public class McpProxyController implements Controller {

    protected final String deploymentId;

    protected final ProxyContext context;

    protected final AsyncTaskExecutor taskExecutor;

    private final DeploymentService deploymentService;

    private final ConsentService consentService;

    private final RateLimiter rateLimiter;

    private final HttpClient httpClient;

    private final UpstreamRouteProvider upstreamRouteProvider;

    private final LogStore logStore;

    private final ApiKeyStore apiKeyStore;

    private final TokenStatsTracker tokenStatsTracker;

    private final AccessService accessService;

    protected final Proxy proxy;

    private String mcpMethodName;

    private boolean useAllowedTools;

    private int redirectCount;


    public McpProxyController(Proxy proxy, ProxyContext context, String deploymentId) {
        this.taskExecutor = proxy.getTaskExecutor();
        this.deploymentService = proxy.getDeploymentService();
        this.rateLimiter = proxy.getRateLimiter();
        this.httpClient = proxy.getClient();
        this.accessService = proxy.getAccessService();
        this.upstreamRouteProvider = proxy.getUpstreamRouteProvider();
        this.logStore = proxy.getLogStore();
        this.context = context;
        this.apiKeyStore = proxy.getApiKeyStore();
        this.tokenStatsTracker = proxy.getTokenStatsTracker();
        this.deploymentId = deploymentId;
        this.consentService = proxy.getConsentService();
        this.proxy = proxy;
    }

    @Override
    public Future<?> handle() {
        useAllowedTools = Boolean.parseBoolean(context.getRequest().getParam("useAllowedTools", "true"));
        return taskExecutor.submit(() -> {
            if (!useAllowedTools && !accessService.hasAdminAccess(context)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Only admin is allowed to view all tools");
            }
            Deployment deployment = deploymentService.findDeployment(context, deploymentId);
            consentService.verifyUserConsent(context, deployment);
            validateDeployment(deployment);
            return deployment;
        }).compose(deployment -> rateLimiter.limit(context, deployment)
                .compose(rateLimitResult -> {
                    Future<?> future;
                    if (rateLimitResult.status() == HttpStatus.OK) {
                        future = handleRateLimitSuccess(deployment);
                    } else {
                        handleRateLimitHit(rateLimitResult);
                        future = Future.succeededFuture();
                    }
                    return future;
                })).otherwise(error -> {
                    handleError(error);
                    return null;
                });
    }

    protected void validateDeployment(Deployment deployment) {
    }

    private void sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        Upstream upstream = route.get();
        Objects.requireNonNull(upstream);
        sendRequest(upstream.getEndpoint());
    }

    private void sendRequest(String absoluteUri) {
        HttpServerRequest request = context.getRequest();
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(absoluteUri)
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation())
                .setConnectTimeout(context.getProxy().getClientOptions().getConnectTimeout())
                .setIdleTimeout(context.getProxy().getClientOptions().getIdleTimeout());
        httpClient.request(options)
                .onSuccess(proxyRequest -> taskExecutor.submit(() -> {
                    handleProxyRequest(proxyRequest);
                    return null;
                }).onFailure(this::handleError))
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        context.setRequestBody(requestBody);
        String contentType = context.getRequest().getHeader(HttpHeaders.CONTENT_TYPE);
        if (Strings.CI.contains(contentType, HEADER_CONTENT_TYPE_APPLICATION_JSON)) {

            try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
                JsonNode tree = ProxyUtil.MAPPER.readTree(stream);
                if (tree.has("method")) {
                    mcpMethodName = tree.get("method").asText();
                }
                if (!isToolCallAllowed(tree)) {
                    respond(HttpStatus.FORBIDDEN, "Tool is not allowed");
                    return;
                }
                if (tree.isObject() && ProxyUtil.processChain((ObjectNode) tree, getRequestEnhancementFunctions())) {
                    context.setRequestBody(Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)));
                }
            } catch (Throwable e) {
                if (e instanceof HttpException httpException) {
                    respond(httpException.getStatus(), httpException.getMessage());
                } else {
                    respond(HttpStatus.BAD_REQUEST, "Invalid JSON request body");
                }
                log.warn("Can't process JSON request body. Error:", e);
                return;
            }
        }
        sendRequest();
    }

    protected List<BaseRequestFunction<ObjectNode>> getRequestEnhancementFunctions() {
        return List.of();
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        HttpConnection connection = proxyRequest.connection();
        log.info("Connected to origin: {}", connection.remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        Deployment deployment = context.getDeployment();

        MultiMap excludeHeaders = MultiMap.caseInsensitiveMultiMap();
        if (!deployment.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }
        injectProxyRequestHeaders(proxyRequest, excludeHeaders);

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);

        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    protected void injectProxyRequestHeaders(HttpClientRequest proxyRequest, MultiMap excludeHeaders) {
    }

    protected String assignPerRequestKey() {
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
        apiKeyStore.assignPerRequestApiKey(proxyApiKeyData);
        return proxyApiKeyData.getPerRequestKey();
    }

    /**
     * Called when proxy received the response headers from the origin.
     */
    private void handleProxyResponse(HttpClientResponse proxyResponse) {
        int responseStatusCode = proxyResponse.statusCode();
        log.info("Received response header from origin: status={}, headers={}", responseStatusCode,
                proxyResponse.headers().size());

        if ((responseStatusCode == 307 || responseStatusCode == 308) && tryFollowRedirect(proxyResponse)) {
            return;
        }

        if (responseStatusCode == HttpStatus.TOO_MANY_REQUESTS.getCode()) {
            UpstreamRoute upstreamRoute = context.getUpstreamRoute();
            upstreamRoute.fail(proxyResponse);
            // get next upstream
            if (canRetry(upstreamRoute)) {
                sendRequest(); // try next
            }
            return;
        }

        if (responseStatusCode == 200) {
            context.getUpstreamRoute().succeed();
        }
        HttpServerResponse response = context.getResponse();

        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        if (Strings.CI.contains(contentType, HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());
            List<String> contentEncodings = proxyResponse.headers().getAll(HttpHeaders.CONTENT_ENCODING);
            proxyResponse.body().onSuccess(body -> handleResponse(responseStatusCode, body, contentEncodings))
                    .onFailure(this::handleResponseError);
        } else {
            handleSseProxyResponse(proxyResponse);
        }
    }

    private void handleSseProxyResponse(HttpClientResponse proxyResponse) {
        List<BaseResponseFunction> fns = buildToolsListResponseFunctions();
        BufferingReadStream.BaseEventListener eventListener = fns.isEmpty()
                ? null
                : new BufferingReadStream.BaseEventListener(fns);

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024), eventListener);

        context.setProxyResponse(proxyResponse);

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse(proxyResponseStream))
                .onFailure(this::handleResponseError);
    }

    private boolean canRetry(UpstreamRoute route) {
        try {
            route.next();
        } catch (HttpException e) {
            respond(e);
            return false;
        }
        return true;
    }

    /**
     * Follows a same-origin 307/308 upstream redirect internally instead of relaying it to the client
     * (e.g. Starlette/FastMCP trailing-slash redirect from {@code /mcp} to {@code /mcp/}).
     * Returns false - leaving the redirect to be relayed as-is - when the Location header is missing or
     * malformed, the redirect cap is hit, or the target is a different origin (so Authorization/API-key
     * headers are never forwarded to a different host).
     */
    private boolean tryFollowRedirect(HttpClientResponse proxyResponse) {
        String location = proxyResponse.getHeader(HttpHeaders.LOCATION);
        if (location == null || redirectCount >= McpClientUtils.MAX_MCP_REDIRECTS) {
            return false;
        }

        URI currentUri;
        URI redirectUri;
        try {
            currentUri = new URI(context.getProxyRequest().absoluteURI());
            // resolve via URI.resolve(URI), not resolve(String) - the latter delegates to URI.create(String),
            // which throws an unchecked IllegalArgumentException on a malformed location instead of the
            // checked URISyntaxException caught below
            redirectUri = currentUri.resolve(new URI(location));
        } catch (URISyntaxException e) {
            log.warn("Ignoring malformed MCP redirect location: {}", location);
            return false;
        }

        if (!McpClientUtils.isSameOrigin(currentUri, redirectUri)) {
            log.warn("Refusing to follow cross-origin MCP redirect from {} to {}", currentUri, redirectUri);
            return false;
        }

        redirectCount++;
        String target = redirectUri.toString();
        log.info("Following MCP redirect ({}) to {}", proxyResponse.statusCode(), target);
        // drain the redirect body so the connection can be released before re-issuing the request
        proxyResponse.body().onComplete(ignored -> sendRequest(target));
        return true;
    }

    /**
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse(BufferingReadStream responseStream) {
        Buffer proxyResponseBody = responseStream.getContent();
        context.setResponseBody(proxyResponseBody);
        finalizeRequest();
        logStore.save(AnalyticsLogContext.from(context, null));
    }

    private void handleResponse(int responseStatus, Buffer proxyResponseBody, List<String> contentEncodings) {
        Future<Buffer> future;
        List<BaseResponseFunction> fns = buildToolsListResponseFunctions();
        if (!fns.isEmpty()) {
            try {
                byte[] decoded = Compression.decodeHttpBody(contentEncodings, proxyResponseBody.getBytes());
                JsonNode tree = ProxyUtil.MAPPER.readTree(decoded);
                Future<JsonNode> chain = Future.succeededFuture(tree);
                for (BaseResponseFunction fn : fns) {
                    chain = chain.compose(fn);
                }
                future = chain.map(result -> Buffer.buffer(result.toString()));
                // We re-serialize the filtered tool list as plain JSON, so the Content-Encoding
                // copied from the origin no longer describes the body we send to the client.
                context.getResponse().headers().remove(HttpHeaders.CONTENT_ENCODING);
            } catch (Throwable e) {
                if (e instanceof HttpException httpException) {
                    respond(httpException.getStatus(), httpException.getMessage());
                } else {
                    respond(HttpStatus.BAD_REQUEST, "Invalid response body from MCP server");
                }
                log.warn("Can't process JSON response body. Error:", e);
                return;
            }
        } else {
            future = Future.succeededFuture(proxyResponseBody);
        }
        future.onSuccess(result -> {
            context.setResponseBody(result);
            respond(responseStatus, result);
            logStore.save(AnalyticsLogContext.from(context, null));
        }).onFailure(error -> {
            log.error("Failed to handle MCP response body", error);
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to handle MCP response body");
        });
    }

    private List<BaseResponseFunction> buildToolsListResponseFunctions() {
        if (!"tools/list".equalsIgnoreCase(mcpMethodName)) {
            return List.of();
        }
        List<BaseResponseFunction> fns = new ArrayList<>();
        if (useAllowedTools) {
            fns.add(new FilterAllowedToolsFn(proxy, context));
        }
        if (hasMcpAppsDomain()) {
            fns.add(new RewriteMcpUiDomainFn(proxy, context));
        }
        return fns;
    }

    private boolean hasMcpAppsDomain() {
        if (context.getDeployment() instanceof Application app
                && app.getMcp() != null
                && app.getMcp().getMcpApps() != null) {
            return app.getMcp().getMcpApps().getDomainOverride() != null;
        }
        return false;
    }

    private boolean isToolCallAllowed(JsonNode tree) {
        if (!"tools/call".equalsIgnoreCase(mcpMethodName) || !useAllowedTools) {
            return true;
        }
        List<String> allowedTools = FilterAllowedToolsFn.getAllowedTools(context.getDeployment());
        if (allowedTools.isEmpty()) {
            return true;
        }
        JsonNode params = tree.get("params");
        if (params == null) {
            return true;
        }
        JsonNode nameNode = params.get("name");
        if (nameNode == null) {
            return true;
        }
        return allowedTools.contains(nameNode.asText());
    }

    private Future<?> handleRateLimitSuccess(Deployment deployment) {
        return tokenStatsTracker.startSpan(context).map(ignore -> {
            UpstreamRoute upstreamRoute = upstreamRouteProvider.get(deployment, null, this::getUpstreamEndpoint);
            if (!canRetry(upstreamRoute)) {
                return null;
            }
            context.setUpstreamRoute(upstreamRoute);
            context.setDeployment(deployment);
            context.setTraceOperation("Send request to %s deployment".formatted(deployment.getName()));
            context.getRequest().body()
                    .onFailure(this::handleRequestBodyError)
                    .onSuccess(this::handleRequestBody);
            return null;
        });
    }

    protected String getUpstreamEndpoint(Deployment deployment) {
        return deployment.getEndpoint();
    }

    private void handleRateLimitHit(RateLimitResult result) {
        ErrorData rateLimitError = new ErrorData();
        rateLimitError.getError().setCode(String.valueOf(result.status().getCode()));
        rateLimitError.getError().setMessage(result.errorMessage());
        rateLimitError.getError().setDisplayMessage(result.displayErrorMessage());

        String errorMessage = ProxyUtil.convertToString(rateLimitError);
        HttpException httpException;
        if (result.replyAfterSeconds() >= 0) {
            Map<String, String> headers = Map.of(HttpHeaders.RETRY_AFTER.toString(), Long.toString(result.replyAfterSeconds()));
            httpException = new HttpException(result.status(), errorMessage, headers);
        } else {
            httpException = new HttpException(result.status(), errorMessage);
        }

        respond(httpException);
        log.warn("Rate limit error {}", result.errorMessage());
    }

    private void handleError(Throwable error) {
        switch (error) {
            case PermissionDeniedException ignored -> {
                respond(HttpStatus.FORBIDDEN, error.getMessage());
                log.warn("Forbidden deployment {}", deploymentId);
            }
            case HttpException httpException -> respond(httpException);
            case ResourceNotFoundException ignored ->
                    respond(HttpStatus.NOT_FOUND, error.getMessage());
            case IllegalArgumentException ignored ->
                    respond(HttpStatus.BAD_REQUEST, error.getMessage());
            case null, default -> {
                String errorMsg = "Error occurred on processing MCP request by deployment: %s".formatted(deploymentId);
                respond(HttpStatus.INTERNAL_SERVER_ERROR, errorMsg);
                log.error(errorMsg, error);
            }
        }
    }

    /**
     * Called when proxy failed to receive request body from the client.
     */
    private void handleRequestBodyError(Throwable error) {
        respond(HttpStatus.UNPROCESSABLE_ENTITY, "Failed to receive body");
        log.warn("Failed to receive client body: {}", error.getMessage());
    }

    /**
     * Called when proxy failed to connect to the origin.
     */
    private void handleProxyConnectionError(Throwable error) {
        log.warn("Can't connect to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (canRetry(upstreamRoute)) {
            sendRequest(); // try next
        }
    }

    /**
     * Called when proxy failed to send request to the origin.
     */
    private void handleProxyRequestError(Throwable error) {
        log.warn("Can't send request to origin: {}", error.getMessage());
        UpstreamRoute upstreamRoute = context.getUpstreamRoute();
        // for 5xx errors we use exponential backoff strategy, so passing retryAfterSeconds parameter makes no sense
        upstreamRoute.fail(HttpStatus.BAD_GATEWAY);
        // get next upstream
        if (canRetry(upstreamRoute)) {
            sendRequest(); // try next
        }
    }

    /**
     * Called when proxy failed to send response to the client.
     */
    private void handleResponseError(Throwable error) {
        log.warn("Can't send response to client: {}", error.getMessage());
        context.getProxyRequest().reset(); // drop connection to stop origin response
        context.getResponse().reset();     // drop connection, so that partial client response won't seem complete
        finalizeRequest();
    }

    private void respond(int status, Buffer result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void respond(HttpStatus status, String result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    protected void finalizeRequest() {
        tokenStatsTracker.endSpan(context).onFailure(error -> log.error("Error occurred at completing span", error));
        ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
        if (proxyApiKeyData != null) {
            apiKeyStore.invalidatePerRequestApiKey(proxyApiKeyData)
                    .onSuccess(invalidated -> {
                        if (!invalidated) {
                            log.warn("Per request is not removed: {}", proxyApiKeyData.getPerRequestKey());
                        }
                    }).onFailure(error -> log.error("error occurred on invalidating per-request key", error));
        }
    }

}
