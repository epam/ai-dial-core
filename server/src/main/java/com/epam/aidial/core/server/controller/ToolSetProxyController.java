package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.credentials.data.credentials.AuthorizationHeader;
import com.epam.aidial.core.credentials.data.credentials.CredentialsLocator;
import com.epam.aidial.core.credentials.service.AuthorizationHeaderProvider;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.limiter.RateLimiter;
import com.epam.aidial.core.server.log.LogStore;
import com.epam.aidial.core.server.security.ApiKeyStore;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.upstream.UpstreamRouteProvider;
import com.epam.aidial.core.server.util.CredentialsLocatorFactory;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.server.vertx.stream.BufferingReadStream;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
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
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import static com.epam.aidial.core.server.Proxy.HEADER_CONTENT_TYPE_APPLICATION_JSON;

@Slf4j
public class ToolSetProxyController implements Controller {

    private static final ArrayNode EMPTY_JSON_ARRAY = ProxyUtil.MAPPER.createArrayNode();

    private final String toolSetId;

    private final CredentialsLocator credentialsLocator;

    private final ProxyContext context;

    private final AsyncTaskExecutor taskExecutor;

    private final DeploymentService deploymentService;

    private final RateLimiter rateLimiter;

    private final HttpClient httpClient;

    private final UpstreamRouteProvider upstreamRouteProvider;

    private final LogStore logStore;

    private final AuthorizationHeaderProvider authorizationHeaderProvider;

    private final ApiKeyStore apiKeyStore;

    private String mcpMethodName;

    public ToolSetProxyController(Proxy proxy, ProxyContext context, String toolSetId) {
        this.taskExecutor = proxy.getTaskExecutor();
        this.deploymentService = proxy.getDeploymentService();
        this.rateLimiter = proxy.getRateLimiter();
        this.httpClient = proxy.getClient();
        this.upstreamRouteProvider = proxy.getUpstreamRouteProvider();
        this.logStore = proxy.getLogStore();
        this.context = context;
        this.authorizationHeaderProvider = proxy.getAuthorizationHeaderProvider();
        this.apiKeyStore = proxy.getApiKeyStore();
        this.toolSetId = toolSetId;
        this.credentialsLocator = CredentialsLocatorFactory.fromAnyUrl(UrlUtil.encodePath(toolSetId), context);
    }

    @Override
    public Future<?> handle() {
        return taskExecutor.submit(() -> {
            Deployment deployment = deploymentService.findDeployment(context, toolSetId);
            if (deployment instanceof ToolSet toolSet) {
                return toolSet;
            }
            throw new ResourceNotFoundException("Toolset is not found: " + toolSetId);
        }).compose(toolSet -> rateLimiter.limit(context, toolSet)
                .map(rateLimitResult -> {
                    if (rateLimitResult.status() == HttpStatus.OK) {
                        handleRateLimitSuccess(toolSet);
                    } else {
                        handleRateLimitHit(rateLimitResult);
                    }
                    return null;
                })).otherwise(error -> {
                    handleError(error);
                    return null;
                });
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
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
                mcpMethodName = tree.get("method").asText();
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

        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers());
        setToolsetCredentials(proxyRequest);
        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    private void setToolsetCredentials(HttpClientRequest proxyRequest) {
        try {
            ToolSet toolSet = (ToolSet) context.getDeployment();
            AuthorizationHeader authorizationHeader = authorizationHeaderProvider.createAuthorizationHeader(
                    credentialsLocator, toolSet.getAuthSettings(), context.getUserSub());
            if (authorizationHeader != null) {
                proxyRequest.putHeader(authorizationHeader.getHeaderName(), authorizationHeader.getHeaderValue());
            }
            if (toolSet.isForwardPerRequestKey()) {
                String perRequestKey = assignPerRequestKey();
                proxyRequest.putHeader(Proxy.HEADER_API_KEY, perRequestKey);
            }
        } catch (ResourceNotFoundException e) {
            log.error(e.getMessage(), e);
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
        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        String contentType = proxyResponse.getHeader(HttpHeaders.CONTENT_TYPE);
        if (Strings.CI.contains(contentType, HEADER_CONTENT_TYPE_APPLICATION_JSON)) {
            proxyResponse.body().onSuccess(body -> handleResponse(responseStatusCode, body))
                    .onFailure(this::handleResponseError);
        } else {
            handleSseProxyResponse(proxyResponse);
        }
    }

    private void handleSseProxyResponse(HttpClientResponse proxyResponse) {
        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setResponseStream(proxyResponseStream);

        HttpServerResponse response = context.getResponse();
        response.setChunked(true);
        response.setStatusCode(proxyResponse.statusCode());

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
        logStore.save(context);
    }

    private void handleResponse(int responseStatus, Buffer proxyResponseBody) {
        if ("tools/list".equalsIgnoreCase(mcpMethodName)) {
            try (InputStream stream = new ByteBufInputStream(proxyResponseBody.getByteBuf())) {
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
                if (filterToolList(tree)) {
                    proxyResponseBody = Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree));
                }
            } catch (Throwable e) {
                if (e instanceof HttpException httpException) {
                    respond(httpException.getStatus(), httpException.getMessage());
                } else {
                    respond(HttpStatus.BAD_REQUEST, "Invalid response body from MCP server");
                }
                log.warn("Can't process JSON response body. Error:", e);
                return;
            }
        }
        context.setResponseBody(proxyResponseBody);
        context.respond(responseStatus, proxyResponseBody);
        logStore.save(context);
    }

    private boolean filterToolList(ObjectNode body) {
        ArrayNode tools = (ArrayNode) Optional.ofNullable(body.get("result")).map(result -> result.get("tools"))
                .filter(JsonNode::isArray).orElse(EMPTY_JSON_ARRAY);
        ToolSet toolSet = (ToolSet) context.getDeployment();
        List<String> allowedTools = toolSet.getAllowedTools();
        if (allowedTools.isEmpty()) {
            return false;
        }
        boolean modified = false;
        for (Iterator<JsonNode> iter = tools.iterator(); iter.hasNext();) {
            JsonNode tool = iter.next();
            String name = tool.get("name").asText();
            if (!allowedTools.contains(name)) {
                iter.remove();
                modified = true;
            }
        }
        return modified;
    }

    private void handleRateLimitSuccess(ToolSet toolSet) {
        UpstreamRoute upstreamRoute = upstreamRouteProvider.get(toolSet, null);
        if (!canRetry(upstreamRoute)) {
            return;
        }
        context.setUpstreamRoute(upstreamRoute);
        context.setDeployment(toolSet);
        context.setTraceOperation("Send request to %s toolset".formatted(toolSet.getName()));
        context.getRequest().body()
                .onFailure(this::handleRequestBodyError)
                .onSuccess(this::handleRequestBody);
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
        if (error instanceof PermissionDeniedException) {
            respond(HttpStatus.FORBIDDEN, error.getMessage());
            log.warn("Forbidden toolset {}", toolSetId);
        } else if (error instanceof HttpException httpException) {
            respond(httpException);
        } else {
            String errorMsg = "Error occurred on processing MCP request by toolset: %s".formatted(toolSetId);
            respond(HttpStatus.INTERNAL_SERVER_ERROR, errorMsg);
            log.error(errorMsg, error);
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
    }

    private void respond(HttpStatus status, String result) {
        context.respond(status, result);
    }

    private void respond(HttpException exception) {
        context.respond(exception);
    }
}
