package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.data.credentials.ResourceCredentials;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.credentials.service.ResourceCredentialsService;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.FilterAllowedToolsFn;
import com.epam.aidial.core.server.function.enhancement.InjectApplicationPropsToMcpRequest;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ConsentService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.token.TokenStatsTracker;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import com.epam.aidial.core.storage.util.UrlUtil;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_ID;
import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;
import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;

@Slf4j
public class ToolSetProxyController implements Controller {

    private final String toolSetId;

    private final CredentialsLocator credentialsLocator;

    private final ProxyContext context;

    private final AsyncTaskExecutor taskExecutor;

    private final DeploymentService deploymentService;

    private final ConsentService consentService;

    private final RateLimiter rateLimiter;

    private final HttpClient httpClient;

    private final UpstreamRouteProvider upstreamRouteProvider;

    private final LogStore logStore;

    private final AuthorizationHeaderProvider authorizationHeaderProvider;

    private final ApiKeyStore apiKeyStore;

    private final TokenStatsTracker tokenStatsTracker;

    private final AccessService accessService;

    private final List<BaseRequestFunction<ObjectNode>> enhancementFunctions;

    private final Proxy proxy;

    private String mcpMethodName;

    private boolean useAllowedTools;

    private final ResourceCredentialsService resourceCredentialsService;
    private final ApplicationSchemaService applicationSchemaService;

    public ToolSetProxyController(Proxy proxy, ProxyContext context, String toolSetId) {
        this.taskExecutor = proxy.getTaskExecutor();
        this.deploymentService = proxy.getDeploymentService();
        this.rateLimiter = proxy.getRateLimiter();
        this.httpClient = proxy.getClient();
        this.accessService = proxy.getAccessService();
        this.upstreamRouteProvider = proxy.getUpstreamRouteProvider();
        this.logStore = proxy.getLogStore();
        this.context = context;
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.apiKeyStore = proxy.getApiKeyStore();
        this.tokenStatsTracker = proxy.getTokenStatsTracker();
        this.toolSetId = toolSetId;
        this.credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(UrlUtil.encodePath(toolSetId), context, ResourceTypes.TOOL_SET);
        this.consentService = proxy.getConsentService();
        this.resourceCredentialsService = proxy.getResourceCredentialsService();
        this.applicationSchemaService = proxy.getApplicationSchemaService();
        this.enhancementFunctions = List.of(new InjectApplicationPropsToMcpRequest(proxy, context));
        this.proxy = proxy;
    }

    @Override
    public Future<?> handle() {
        useAllowedTools = Boolean.parseBoolean(context.getRequest().getParam("useAllowedTools", "true"));
        return taskExecutor.submit(() -> {
            if (!useAllowedTools && !accessService.hasAdminAccess(context)) {
                throw new HttpException(HttpStatus.FORBIDDEN, "Only admin is allowed to view all tools");
            }
            Deployment deployment = deploymentService.findDeployment(context, toolSetId);
            if (deployment instanceof ToolSet toolSet) {
                consentService.verifyUserConsent(context, deployment);
                return toolSet;
            } else if (deployment instanceof Application application) {
                consentService.verifyUserConsent(context, deployment);
                resolveMcpProperties(application);
                return application;
            }
            throw new ResourceNotFoundException("Toolset is not found: " + toolSetId);
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

    private void resolveMcpProperties(Application application) {
        Application.Mcp mcp;
        if (application.hasApplicationTypeSchemaId()) {
            mcp = applicationSchemaService.getMcp(application);
            application.setMcp(mcp);
        } else {
            mcp = application.getMcp();
        }
        if (mcp == null) {
            throw new IllegalArgumentException("Application doesn't support MCP protocol: " + application.getName());
        }
    }

    private void sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        Upstream upstream = route.get();
        Objects.requireNonNull(upstream);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(upstream.getEndpoint())
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation())
                .setConnectTimeout(context.getProxy().getClientOptions().getConnectTimeout())
                .setIdleTimeout(context.getProxy().getClientOptions().getIdleTimeout());
        httpClient.request(options)
                .onSuccess(proxyRequest -> taskExecutor.submit(() -> {
                    handleProxyRequest(proxyRequest);
                    return null;
                }))
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
                if (tree.isObject() && ProxyUtil.processChain((ObjectNode) tree, enhancementFunctions)) {
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
        excludeHeaders.add(HEADER_APPLICATION_PROPERTIES, "whatever");
        excludeHeaders.add(HEADER_APPLICATION_ID, "whatever");

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);

        if ((deployment instanceof Application application
                && application.hasApplicationTypeSchemaId()
                && application.getMcp().getConfigDelivery() == Application.McpConfigDelivery.HEADER)) {
            proxyRequest.putHeader(HEADER_APPLICATION_ID, deployment.getName());

            applicationSchemaService.consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
                if (appendApplicationPropertiesHeader) {
                    String propsString = ProxyUtil.MAPPER.writeValueAsString(properties);
                    proxyRequest.putHeader(HEADER_APPLICATION_PROPERTIES, propsString);
                }
            });
        }

        setToolsetCredentials(proxyRequest);
        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    private void setToolsetCredentials(HttpClientRequest proxyRequest) {
        Deployment deployment = context.getDeployment();
        try {
            if (deployment instanceof ToolSet toolSet) {
                ResourceCredentials resourceCredentials = resourceCredentialsService.getRefreshedResourceCredentials(
                        credentialsLocator, toolSet.getAuthSettings(), context.getInitiatorId()
                );

                if (resourceCredentials != null) {
                    log.debug("Credentials found: User: {}, Resource: {}, CredentialsLevel: {}",
                            context.getUserId(), toolSetId, resourceCredentials.getCredentialsLevel());
                    addAuthorizationHeader(proxyRequest, resourceCredentials);
                } else {
                    log.debug("Credentials not found - User: {}, Resource: {}", context.getUserId(), toolSetId);
                }

                if (toolSet.isForwardPerRequestKey()) {
                    String perRequestKey = assignPerRequestKey();
                    proxyRequest.putHeader(Proxy.HEADER_API_KEY, perRequestKey);
                }

            } else if (deployment instanceof Application application) {
                Application.Mcp mcp = application.getMcp();
                if (mcp.isForwardPerRequestKey()) {
                    String perRequestKey = assignPerRequestKey();
                    proxyRequest.putHeader(Proxy.HEADER_API_KEY, perRequestKey);
                }
            }
        } catch (Exception e) {
            log.error("Can't provide credentials to toolset due to the error: {}", e.getMessage(), e);
        }
    }

    private void addAuthorizationHeader(HttpClientRequest proxyRequest,
                                        ResourceCredentials resourceCredentials) {
        AuthorizationHeader authorizationHeader = authorizationHeaderProvider.createAuthorizationHeader(resourceCredentials);
        if (authorizationHeader != null) {
            log.debug("AuthorizationHeader added: User: {}, Resource: {}", context.getUserId(), toolSetId);
            proxyRequest.putHeader(authorizationHeader.getHeaderName(), authorizationHeader.getHeaderValue());
        }
    }

    private String assignPerRequestKey() {
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
            proxyResponse.body().onSuccess(body -> handleResponse(responseStatusCode, body))
                    .onFailure(this::handleResponseError);
        } else {
            handleSseProxyResponse(proxyResponse);
        }
    }

    private void handleSseProxyResponse(HttpClientResponse proxyResponse) {
        BufferingReadStream.BaseEventListener eventListener = null;
        if (requireToolFiltering()) {
            FilterAllowedToolsFn fn = new FilterAllowedToolsFn(proxy, context);
            eventListener = new BufferingReadStream.BaseEventListener(fn);
        }

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024), eventListener);

        context.setProxyResponse(proxyResponse);
        context.setResponseStream(proxyResponseStream);

        HttpServerResponse response = context.getResponse();
        ProxyUtil.handleChunkedResponse(response, proxyResponse);

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .to(response)
                .onSuccess(ignored -> handleResponse())
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
     * Called when proxy sent response from the origin to the client.
     */
    private void handleResponse() {
        Buffer proxyResponseBody = context.getResponseStream().getContent();
        context.setResponseBody(proxyResponseBody);
        finalizeRequest();
        logStore.save(context);
    }

    private void handleResponse(int responseStatus, Buffer proxyResponseBody) {
        Future<Buffer> future;
        if (requireToolFiltering()) {
            try (InputStream stream = new ByteBufInputStream(proxyResponseBody.getByteBuf())) {
                JsonNode tree = ProxyUtil.MAPPER.readTree(stream);
                FilterAllowedToolsFn fn = new FilterAllowedToolsFn(proxy, context);
                future = fn.apply(tree).map(result -> Buffer.buffer(result.toString()));
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
            logStore.save(context);
        }).onFailure(error -> {
            log.error("Failed to handle MCP response body", error);
            respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to handle MCP response body");
        });
    }

    private boolean requireToolFiltering() {
        return "tools/list".equalsIgnoreCase(mcpMethodName) && useAllowedTools;
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
            context.setTraceOperation("Send request to %s toolset".formatted(deployment.getName()));
            context.getRequest().body()
                    .onFailure(this::handleRequestBodyError)
                    .onSuccess(this::handleRequestBody);
            return null;
        });
    }

    private String getUpstreamEndpoint(Deployment deployment) {
        if (deployment instanceof Application application) {
            return application.getMcp().getEndpoint();
        }
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
                log.warn("Forbidden toolset {}", toolSetId);
            }
            case HttpException httpException -> respond(httpException);
            case ResourceNotFoundException ignored ->
                    respond(HttpStatus.NOT_FOUND, error.getMessage());
            case IllegalArgumentException ignored ->
                    respond(HttpStatus.BAD_REQUEST, error.getMessage());
            case null, default -> {
                String errorMsg = "Error occurred on processing MCP request by toolset: %s".formatted(toolSetId);
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
