package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.CollectRequestCustomAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseCustomAttachmentsFn;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
public class ApplicationRouteController extends BaseRouteController {

    private final String deploymentId;

    private final String routePath;

    public ApplicationRouteController(Proxy proxy, ProxyContext context, String deploymentId, String routePath) {
        super(proxy, context);
        this.deploymentId = deploymentId;
        this.routePath = routePath;
        this.enhancementFunctions.add(new CollectRequestCustomAttachmentsFn(proxy, context));
    }

    @Override
    protected Future<Boolean> hasRequiredPermissions(Set<ResourceAccessType> permissions) {
        ResourceDescriptor appResource;
        try {
            appResource = ResourceDescriptorFactory.fromAnyUrl(deploymentId, proxy.getEncryptionService());
        } catch (IllegalArgumentException e) {
            // it looks like deployment id is not a custom application
            return Future.succeededFuture(true);
        }
        return proxy.getVertx().executeBlocking(() -> {
            Map<ResourceDescriptor, Set<ResourceAccessType>> result = proxy.getAccessService().lookupPermissions(Set.of(appResource), context);
            Set<ResourceAccessType> actual = result.get(appResource);
            if (actual == null) {
                return false;
            }
            Set<ResourceAccessType> expected = permissions.isEmpty() ? ResourceAccessType.READ_ONLY : permissions;
            return actual.containsAll(expected);
        });
    }

    @Override
    protected void injectAdditionalHeaders(HttpClientRequest proxyRequest) {
        proxyRequest.putHeader(Proxy.HEADER_APPLICATION_ID, deploymentId);
    }

    @Override
    protected Future<Void> handleProxyResponseBody(Buffer responseBody) {
        try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
            ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
            var fn = new CollectResponseCustomAttachmentsFn(proxy, context);
            return fn.apply(tree);
        } catch (IOException e) {
            log.warn("Can't parse JSON response body. Trace: {}. Span: {}. Error:",
                    context.getTraceId(), context.getSpanId(), e);
            return Future.failedFuture(e);
        }
    }

    @Override
    protected Future<Collection<Route>> getRoutes() {
        return proxy.getVertx().executeBlocking(() -> {
            Deployment deployment = proxy.getDeploymentService().findDeployment(context, deploymentId);
            context.setDeployment(deployment);
            if (deployment instanceof Application application) {
                Map<String, Route> routes = proxy.getApplicationSchemaService().getRoutes(application);
                if (routes == null) {
                    routes = application.getRoutes();
                }
                return sortRoutes(routes);
            } else {
                throw new HttpException(HttpStatus.NOT_FOUND, "Application is not found: " + deploymentId);
            }
        });
    }

    private Collection<Route> sortRoutes(Map<String, Route> routes) {
        return routes.entrySet().stream().map(e -> {
            Route route = e.getValue();
            route.setName(e.getKey());
            return route;
        }).sorted(Comparator.comparingInt(Route::getOrder)).collect(Collectors.toList());
    }

    @Override
    protected String getRoutePath() {
        return routePath;
    }


}
