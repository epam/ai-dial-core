package com.epam.aidial.core.server.controller.route;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.CollectRequestApplicationFilesFn;
import com.epam.aidial.core.server.function.CollectRequestCustomAttachmentsFn;
import com.epam.aidial.core.server.function.CollectResponseCustomAttachmentsFn;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.util.UrlUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.netty.buffer.ByteBufInputStream;
import io.vertx.core.Future;
import io.vertx.core.MultiMap;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpClientRequest;
import io.vertx.core.http.HttpHeaders;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.epam.aidial.core.server.Proxy.HEADER_APPLICATION_PROPERTIES;

@Slf4j
public class ApplicationRouteController extends BaseRouteController {

    private final String deploymentId;

    private final String routePath;

    public ApplicationRouteController(Proxy proxy, ProxyContext context, String deploymentId, String routePath) {
        super(proxy, context);
        this.deploymentId = deploymentId;
        this.routePath = routePath;
    }

    @Override
    protected Future<Boolean> hasRequiredPermissions(Set<ResourceAccessType> permissions) {
        if (context.getConfig().selectDeployment(deploymentId) != null) {
            return Future.succeededFuture(true);
        }
        ResourceDescriptor appResource;
        try {
            String encodedPath = UrlUtil.encodePath(deploymentId);
            appResource = ResourceDescriptorFactory.fromAnyUrl(encodedPath, proxy.getEncryptionService());
        } catch (IllegalArgumentException e) {
            // it looks like deployment id is not a custom application
            return Future.succeededFuture(true);
        }
        return proxy.getTaskExecutor().submit(() -> {
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
    protected boolean hasAccessByUserRoles(Route route) {
        if (route.getUserRoles() != null) {
            return super.hasAccessByUserRoles(route);
        }
        return context.getDeployment().hasAccess(context.getUserRoles());
    }

    @Override
    protected void injectAdditionalHeaders(HttpClientRequest proxyRequest) {
        proxyRequest.putHeader(Proxy.HEADER_APPLICATION_ID, deploymentId);
        if ((context.getDeployment() instanceof Application application && application.hasApplicationTypeSchemaId())) {
            proxy.getApplicationSchemaService().consumeMetadataProperties(application, (properties, appendApplicationPropertiesHeader) -> {
                if (appendApplicationPropertiesHeader) {
                    String propsString = ProxyUtil.MAPPER.writeValueAsString(properties);
                    proxyRequest.putHeader(HEADER_APPLICATION_PROPERTIES, propsString);
                }
            });
        }
    }

    @Override
    protected Future<Void> handleProxyResponseBody(Buffer responseBody) {
        Route route = context.getRoute();
        if (!route.getAttachmentPaths().getResponseBody().isEmpty()) {
            try (InputStream stream = new ByteBufInputStream(responseBody.getByteBuf())) {
                ObjectNode tree = (ObjectNode) ProxyUtil.MAPPER.readTree(stream);
                var fn = new CollectResponseCustomAttachmentsFn(proxy, context);
                return fn.apply(tree).map(ignored -> null);
            } catch (Throwable e) {
                log.warn("Can't parse JSON response body. Error:", e);
                return Future.failedFuture(e);
            }
        }
        return Future.succeededFuture();
    }

    @Override
    protected Future<Collection<Route>> getRoutes() {
        return proxy.getTaskExecutor().submit(() -> {
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

    @Override
    protected void setupEnhancementFunctions() {
        Route route = context.getRoute();
        if (!route.getAttachmentPaths().getRequestBody().isEmpty()) {
            enhancementFunctions.add(new CollectRequestCustomAttachmentsFn(proxy, context));
        }
        enhancementFunctions.add(new CollectRequestApplicationFilesFn(proxy, context));
    }

    @Override
    protected MultiMap excludeHeaders() {
        Deployment deployment = context.getDeployment();
        MultiMap excludeHeaders = super.excludeHeaders();
        if (!deployment.isForwardAuthToken()) {
            excludeHeaders.add(HttpHeaders.AUTHORIZATION, "whatever");
        }
        return excludeHeaders;
    }
}
