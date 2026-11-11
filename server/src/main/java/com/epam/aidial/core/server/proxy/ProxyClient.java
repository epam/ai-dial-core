package com.epam.aidial.core.server.proxy;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.ApiKeyData;
import com.epam.aidial.core.server.plugin.PluginManager;
import com.epam.aidial.core.server.proxy.embedded.EmbeddedProxyRequest;
import com.epam.aidial.core.server.proxy.http.HttpProxyRequest;
import com.epam.aidial.core.server.util.DeploymentEndpointUtil;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.RequestOptions;
import io.vertx.core.impl.ContextInternal;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nullable;

/**
 * Creates the {@link ProxyRequest} to the origin serving a deployment: an HTTP request, or a call to the plugin an
 * interface names.
 */
@RequiredArgsConstructor
public class ProxyClient {

    private static final String PLATFORM_APPLICATIONS = "applications/" + ResourceDescriptor.PLATFORM_BUCKET + "/";

    private final HttpClient client;
    private final Vertx vertx;
    private final PluginManager pluginManager;

    public Future<ProxyRequest> http(RequestOptions options) {
        return client.request(options).map(HttpProxyRequest::new);
    }

    /**
     * Creates the request to {@code plugin}, which serves an interface of the context's deployment.
     * {@code absoluteUri} is the {@code plugin://<name>} url the interface resolves to, with the path and query.
     *
     * <p>Every failure comes back as a failed future, for the caller's connect-failure path: no {@code plugins} entry
     * by that name, a provider that is not loaded or serves no requests, a plugin that cannot be created, or an
     * application that is not of the platform config. A plugin runs platform code, so a user's custom application is
     * never served by one.
     */
    public Future<ProxyRequest> embedded(ProxyContext context, HttpMethod method, String absoluteUri, String plugin) {
        // on a virtual thread that AsyncTaskExecutor dispatched, this is the request's context, as on its event loop
        ContextInternal vertxContext = (ContextInternal) vertx.getOrCreateContext();
        try {
            Config config = context.getConfig();
            if (context.getDeployment() instanceof Application application && !isPlatform(config, application)) {
                throw new IllegalStateException("A plugin is reserved for platform deployments: " + application.getName());
            }
            // fails here, for an entry that cannot serve; each attempt takes the plugin when it is sent
            pluginManager.resolve(config, plugin);
            String baseUrl = DeploymentEndpointUtil.PLUGIN_URL_PREFIX + plugin;
            return vertxContext.succeededFuture(new EmbeddedProxyRequest(vertxContext, context,
                    () -> pluginManager.acquire(config, plugin), method, baseUrl, absoluteUri));
        } catch (RuntimeException e) {
            return vertxContext.failedFuture(e);
        }
    }

    /**
     * Creates the decide or notice request of a router in decision mode to {@code plugin}, which serves the router's
     * interface. Unlike {@link #embedded}: the context's deployment is not checked (it is the router, or at a redecide or
     * notice the candidate that failed); the attempt's calls carry {@code callKey}, the decide request's key, and are
     * refused when it is null, as for a notice; the plugin gets {@code bodyTree} and not the context's request tree; the
     * attempt does not watch the client; and it closes once the plugin's answer has been read or has failed.
     */
    public Future<ProxyRequest> embeddedForRouter(ProxyContext context, HttpMethod method, String absoluteUri, String plugin,
                                                  @Nullable ApiKeyData callKey, @Nullable ObjectNode bodyTree) {
        ContextInternal vertxContext = (ContextInternal) vertx.getOrCreateContext();
        try {
            Config config = context.getConfig();
            pluginManager.resolve(config, plugin);
            String baseUrl = DeploymentEndpointUtil.PLUGIN_URL_PREFIX + plugin;
            return vertxContext.succeededFuture(new EmbeddedProxyRequest(vertxContext, context,
                    () -> pluginManager.acquire(config, plugin), method, baseUrl, absoluteUri,
                    new EmbeddedProxyRequest.RouterRequest(callKey, bodyTree)));
        } catch (RuntimeException e) {
            return vertxContext.failedFuture(e);
        }
    }

    /**
     * A config application is keyed by its name. One loaded from a bucket carries its resource url as its name, which
     * every write path sets: {@code applications/platform/...} for the platform bucket, an encrypted bucket id otherwise.
     */
    private static boolean isPlatform(Config config, Application application) {
        String name = application.getName();
        return config.getApplications().containsKey(name) || name.startsWith(PLATFORM_APPLICATIONS);
    }
}
