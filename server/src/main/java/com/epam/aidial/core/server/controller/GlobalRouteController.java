package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.Set;

@Slf4j
public class GlobalRouteController extends BaseRouteController {

    public GlobalRouteController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Future<Boolean> hasRequiredPermissions(Set<ResourceAccessType> permissions) {
        return Future.succeededFuture(true);
    }

    @Override
    protected void injectAdditionalHeaders(HttpClientRequest proxyRequest) {
        // do nothing
    }

    @Override
    protected Future<Void> handleProxyResponseBody(Buffer responseBody) {
        return Future.succeededFuture();
    }

    @Override
    protected Future<Collection<Route>> getRoutes() {
        return Future.succeededFuture(context.getConfig().getRoutes().values());
    }

    @Override
    protected String getRoutePath() {
        return context.getRequest().path();
    }
}
