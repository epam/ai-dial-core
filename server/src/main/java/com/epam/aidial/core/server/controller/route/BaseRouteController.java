package com.epam.aidial.core.server.controller.route;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.Controller;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.data.ErrorData;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import com.epam.aidial.core.server.limiter.RateLimitResult;
import com.epam.aidial.core.server.log.AnalyticsLogContext;
import com.epam.aidial.core.server.upstream.UpstreamRoute;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import io.vertx.core.http.HttpServerRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.Strings;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import javax.annotation.Nullable;

@Slf4j
@RequiredArgsConstructor
abstract class BaseRouteController implements Controller {

    protected final Proxy proxy;
    protected final ProxyContext context;

    protected final List<BaseRequestFunction<RequestObject>> enhancementFunctions = new ArrayList<>();

    @Override
    public Future<?> handle() {
        return getRoutes().map(this::selectRoute).compose(this::handleRoute).otherwise(error -> {
            handleError(error);
            return null;
        });
    }

    protected Future<?> handleRoute(Route route) {
        if (route == null) {
            respond(HttpStatus.NOT_FOUND, "Route is not found");
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
                    .compose(rateLimitResult -> {
                        Future<?> future;
                        if (rateLimitResult.status() == HttpStatus.OK) {
                            future = handleRateLimitSuccess();
                        } else {
                            handleRateLimitHit(rateLimitResult);
                            future = Future.succeededFuture();
                        }
                        return future;
                    });
        });
    }

    boolean canRetry(UpstreamRoute route) {
        try {
            route.next();
        } catch (HttpException e) {
            respond(e);
            return false;
        }
        return true;
    }

    private Future<?> handleRateLimitSuccess() {
        if (context.getResponseBody() == null) {
            return proxy.getTokenStatsTracker().startSpan(context)
                    .compose(ignore -> {
                        if (isWebSocketUpgrade(context.getRequest())) {
                            RouteWebSocketHandler handler = new RouteWebSocketHandler(context, proxy, this);
                            return handler.handle();
                        } else {
                            RouteRequestBodyHandler handler = new RouteRequestBodyHandler(context, proxy, this);
                            return handler.handle();
                        }
                    });
        } else {
            context.getResponse().send(context.getResponseBody());
            proxy.getLogStore().save(AnalyticsLogContext.from(context, null));
            return Future.succeededFuture();
        }
    }

    private static boolean isWebSocketUpgrade(HttpServerRequest request) {
        String upgradeHeader = request.getHeader(HttpHeaders.UPGRADE);
        return Strings.CI.contains(upgradeHeader, "websocket");
    }

    protected abstract Future<Boolean> hasRequiredPermissions(Set<ResourceAccessType> permissions);

    protected boolean hasAccessByUserRoles(Route route) {
        return route.hasAccess(context.getUserRoles());
    }

    String getRequestUri() {
        HttpServerRequest request = context.getRequest();
        return request.uri();
    }

    protected void setupEnhancementFunctions() {
        // do nothing by default
    }

    @Nullable
    ApiKeyData setupProxyApiKeyData() {
        Upstream upstream = context.getUpstreamRoute().get();
        if (upstream != null && upstream.getKey() != null) {
            return null;
        }
        ApiKeyData proxyApiKeyData = new ApiKeyData();
        context.setProxyApiKeyData(proxyApiKeyData);
        ApiKeyData.initFromContext(proxyApiKeyData, context);
        return proxyApiKeyData;
    }

    void copyHeaders(MultiMap from, MultiMap to) {
        Upstream upstream = context.getUpstreamRoute().get();
        MultiMap excludeHeaders = excludeHeaders();
        ProxyUtil.copyHeaders(from, to, excludeHeaders);
        if (upstream != null && upstream.getKey() != null) {
            to.add(Proxy.HEADER_API_KEY, upstream.getKey());
        } else {
            ApiKeyData proxyApiKeyData = context.getProxyApiKeyData();
            to.add(Proxy.HEADER_API_KEY, proxyApiKeyData.getPerRequestKey());
        }
    }

    protected MultiMap excludeHeaders() {
        return MultiMap.caseInsensitiveMultiMap();
    }

    protected abstract void injectAdditionalHeaders(HttpClientRequest proxyRequest);

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

    protected abstract String getRoutePath();

    void respond(HttpStatus status, String result) {
        finalizeRequest();
        context.respond(status, result);
    }

    void respond(HttpException exception) {
        finalizeRequest();
        context.respond(exception);
    }

    void respond(HttpStatus status) {
        finalizeRequest();
        context.respond(status);
    }

    void finalizeRequest() {
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
}