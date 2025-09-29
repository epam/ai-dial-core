package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
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
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.http.RequestOptions;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.client.utils.URLEncodedUtils;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@RequiredArgsConstructor
public abstract class BaseRouteController implements Controller {

    protected final Proxy proxy;
    protected final ProxyContext context;

    protected final List<BaseRequestFunction<ObjectNode>> enhancementFunctions = new ArrayList<>();

    @Override
    public Future<?> handle() {
        return getRoutes().map(this::selectRoute).compose(this::handleRoute).otherwise(error -> {
            handleError(error);
            return null;
        });
    }

    protected Future<?> handleRoute(Route route) {
        if (route == null) {
            respond(HttpStatus.BAD_GATEWAY, "No route");
            log.warn("RouteController can't find a route to proceed the request: {}", getRequestUri());
            return Future.succeededFuture();
        }
        context.setRoute(route);

        if (!hasAccessByUserRoles(route)) {
            respond(HttpStatus.FORBIDDEN, "Forbidden route");
            log.warn("Forbidden route {}", route.getName());
            return Future.succeededFuture();
        }

        return hasRequiredPermissions(route.getPermissions()).compose(result -> {
            if (!result) {
                // the route has no required permissions
                context.respond(HttpStatus.FORBIDDEN, "Forbidden route");
                return Future.succeededFuture();
            }
            Route.Response response = route.getResponse();
            if (response == null) {
                UpstreamRoute upstreamRoute = proxy.getUpstreamRouteProvider().get(route);
                if (!canRetry(upstreamRoute)) {
                    return Future.succeededFuture();
                }
                context.setTraceOperation("Send request to %s route".formatted(route.getName()));
                context.setUpstreamRoute(upstreamRoute);
            } else {
                context.getResponse().setStatusCode(response.getStatus());
                context.setResponseBody(Buffer.buffer(response.getBody()));
            }
            return proxy.getRateLimiter().limit(context, context.getRoute())
                    .map(rateLimitResult -> {
                        if (rateLimitResult.status() == HttpStatus.OK) {
                            handleRateLimitSuccess();
                        } else {
                            handleRateLimitHit(rateLimitResult);
                        }
                        return null;
                    });
        });
    }

    private void handleRateLimitSuccess() {
        if (context.getResponseBody() == null) {
            setupProxyApiKeyData();
            context.getRequest().body()
                    .onFailure(this::handleRequestBodyError)
                    .onSuccess(body -> proxy.getTaskExecutor().submit(() -> {
                        handleRequestBody(body);
                        return null;
                    }));
        } else {
            context.getResponse().send(context.getResponseBody());
            proxy.getLogStore().save(context);
        }
    }

    protected abstract Future<Boolean> hasRequiredPermissions(Set<ResourceAccessType> permissions);

    protected boolean hasAccessByUserRoles(Route route) {
        return route.hasAccess(context.getUserRoles());
    }

    String getRequestUri() {
        HttpServerRequest request = context.getRequest();
        return request.uri();
    }

    @SneakyThrows
    private Future<?> sendRequest() {
        UpstreamRoute route = context.getUpstreamRoute();
        HttpServerRequest request = context.getRequest();

        Upstream upstream = route.get();
        Objects.requireNonNull(upstream);
        RequestOptions options = new RequestOptions()
                .setAbsoluteURI(getEndpointUri(upstream))
                .setMethod(request.method())
                .setTraceOperation(context.getTraceOperation())
                .setConnectTimeout(context.getProxy().getClientOptions().getConnectTimeout())
                .setIdleTimeout(context.getProxy().getClientOptions().getIdleTimeout());

        return proxy.getClient().request(options)
                .onSuccess(this::handleProxyRequest)
                .onFailure(this::handleProxyConnectionError);
    }

    private void handleRequestBody(Buffer requestBody) {
        Deployment deployment = context.getDeployment();
        log.info("Received body from client. Deployment: {}. Length: {}",
                deployment == null ? "N/A" : deployment.getName(), requestBody.length());

        context.setRequestBodyTimestamp(System.currentTimeMillis());
        context.setRequestBody(requestBody);

        setupEnhancementFunctions();

        if (!enhancementFunctions.isEmpty()) {
            try (InputStream stream = new ByteBufInputStream(requestBody.getByteBuf())) {
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
                if (ProxyUtil.processChain(tree, enhancementFunctions)) {
                    context.setRequestBody(Buffer.buffer(ProxyUtil.MAPPER.writeValueAsBytes(tree)));
                }
            } catch (Throwable e) {
                if (e instanceof HttpException httpException) {
                    respond(httpException.getStatus(), httpException.getMessage());
                } else {
                    respond(HttpStatus.BAD_REQUEST);
                }
                log.warn("Can't process JSON request body. Error:", e);
                return;
            }
        }

        proxy.getApiKeyStore().assignPerRequestApiKey(context.getProxyApiKeyData());

        sendRequest();
    }

    protected void setupEnhancementFunctions() {
        // do nothing by default
    }

    private void setupProxyApiKeyData() {
        Upstream upstream = context.getUpstreamRoute().get();
        if (upstream != null && upstream.getKey() != null) {
            return;
        }
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
    }

    /**
     * Called when proxy connected to the origin.
     */
    private void handleProxyRequest(HttpClientRequest proxyRequest) {
        log.info("Connected to origin: {}", proxyRequest.connection().remoteAddress());

        HttpServerRequest request = context.getRequest();
        context.setProxyRequest(proxyRequest);

        Upstream upstream = context.getUpstreamRoute().get();
        MultiMap excludeHeaders = excludeHeaders();
        ProxyUtil.copyHeaders(request.headers(), proxyRequest.headers(), excludeHeaders);
        if (upstream != null && upstream.getKey() != null) {
            proxyRequest.putHeader(Proxy.HEADER_API_KEY, upstream.getKey());
        } else {
            ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
            proxyRequest.headers().add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());
        }

        Buffer proxyRequestBody = context.getRequestBody();
        proxyRequest.putHeader(HttpHeaders.CONTENT_LENGTH, Integer.toString(proxyRequestBody.length()));

        injectAdditionalHeaders(proxyRequest);

        proxyRequest.send(proxyRequestBody)
                .onSuccess(this::handleProxyResponse)
                .onFailure(this::handleProxyRequestError);
    }

    protected MultiMap excludeHeaders() {
        return MultiMap.caseInsensitiveMultiMap();
    }

    protected abstract void injectAdditionalHeaders(HttpClientRequest proxyRequest);

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
            proxy.getRateLimiter().increase(context, context.getRoute()).onFailure(error -> log.warn("Failed to increase limit", error));
        }

        BufferingReadStream proxyResponseStream = new BufferingReadStream(proxyResponse,
                ProxyUtil.contentLength(proxyResponse, 1024));

        context.setProxyResponse(proxyResponse);
        context.setResponseStream(proxyResponseStream);

        HttpServerResponse response = context.getResponse();
        response.setChunked(true);
        response.setStatusCode(responseStatusCode);
        ProxyUtil.copyHeaders(proxyResponse.headers(), response.headers());

        proxyResponseStream.pipe()
                .endOnFailure(false)
                .endOnSuccess(false)
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
        BufferingReadStream responseStream = context.getResponseStream();
        Buffer proxyResponseBody = responseStream.getContent();
        context.setResponseBody(proxyResponseBody);
        handleProxyResponseBody(proxyResponseBody).onComplete(result -> {
            if (result.failed()) {
                log.warn("Failed to handle proxy response", result.cause());
            }
            HttpServerResponse response = context.getResponse();
            responseStream.end(response);
            proxy.getLogStore().save(context);
            finalizeRequest();
        });
    }

    protected abstract Future<Void> handleProxyResponseBody(Buffer responseBody);

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
        
        log.warn("Rate limit error {}. Route: {}", result.errorMessage(), context.getRoute().getName());
    }

    private void handleError(Throwable error) {
        if (error instanceof HttpException httpException) {
            respond(httpException);
        } else {
            String errorMsg = "Error occurred on processing route request: %s".formatted(context.getRequest().path());
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
        finalizeRequest();
    }

    protected abstract Future<Collection<Route>> getRoutes();

    private Route selectRoute(Collection<Route> routes) {
        HttpServerRequest request = context.getRequest();
        String path = getRoutePath();

        for (Route route : routes) {
            List<Pattern> paths = route.getPaths();
            Set<String> methods = route.getMethods();

            if (!methods.isEmpty() && !methods.contains(request.method().name())) {
                continue;
            }

            if (paths.isEmpty()) {
                return route;
            }

            for (Pattern pattern : route.getPaths()) {
                if (pattern.matcher(path).matches()) {
                    return route;
                }
            }
        }

        return null;
    }

    @SneakyThrows
    private String getEndpointUri(Upstream upstream) {
        URIBuilder uriBuilder = new URIBuilder(upstream.getEndpoint());
        if (context.getRoute().isRewritePath()) {
            uriBuilder.setPath(getRoutePath());
            String query = context.getRequest().query();
            if (query != null) {
                uriBuilder.setParameters(URLEncodedUtils.parse(query, StandardCharsets.UTF_8));
            }
        }
        return uriBuilder.toString();
    }

    protected abstract String getRoutePath();

    private void respond(HttpStatus status, String result) {
        finalizeRequest();
        context.respond(status, result);
    }

    private void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    private void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    private void finalizeRequest() {
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