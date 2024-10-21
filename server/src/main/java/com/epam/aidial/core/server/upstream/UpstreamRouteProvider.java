package com.epam.aidial.core.server.upstream;

import com.epam.aidial.core.config.Addon;
import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Assistant;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.Upstream;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides UpstreamRoute for the given UpstreamProvider.
 * This class caches load balancers for deployments and routes from config,
 * for other deployments (for example: custom applications) each request will build a new load balancer.
 * If upstreams configuration for any deployment changed - load balancer state will be invalidated.
 */
@Slf4j
public class UpstreamRouteProvider {

    /**
     * Indicated max retry attempts (max upstreams from load balancer) to route a single user request
     */
    private static final int MAX_RETRY_COUNT = 5;

    /**
     * Cached load balancers for config deployments
     */
    private volatile Map<String, TieredBalancer> balancers = new HashMap<>();

    /**
     * Returns UpstreamRoute for the given provider
     *
     * @param provider upstream provider for any deployment with actual upstreams
     * @return upstream route
     */
    public UpstreamRoute get(UpstreamProvider provider) {
        String deploymentName = provider.getName();
        List<Upstream> upstreams = provider.getUpstreams();

        TieredBalancer balancer = balancers.get(deploymentName);
        if (balancer == null) {
            // if no state found for upstream, it's probably custom application
            balancer = new TieredBalancer(deploymentName, upstreams);
        }

        return new UpstreamRoute(balancer, MAX_RETRY_COUNT);
    }

    public synchronized void onUpdate(Config config) {
        log.debug("Updating load balancers state");
        Map<String, TieredBalancer> oldState = balancers;
        Map<String, TieredBalancer> newState = new HashMap<>();

        Map<String, Model> models = config.getModels();
        updateDeployments(newState, oldState, models.values());

        Map<String, Application> applications = config.getApplications();
        updateDeployments(newState, oldState, applications.values());

        Map<String, Addon> addons = config.getAddons();
        updateDeployments(newState, oldState, addons.values());

        Map<String, Assistant> assistants = config.getAssistant().getAssistants();
        updateDeployments(newState, oldState, assistants.values());

        LinkedHashMap<String, Route> routes = config.getRoutes();
        updateRoutes(newState, oldState, routes.values());

        balancers = newState;
    }

    private static void updateRoutes(Map<String, TieredBalancer> newState, Map<String, TieredBalancer> oldState, Collection<Route> routes) {
        for (Route route : routes) {
            String name = route.getName();

            RouteEndpointProvider endpointProvider = new RouteEndpointProvider(route);
            TieredBalancer previous = oldState.get(name);
            updateDeployment(endpointProvider, previous, newState);
        }
    }

    private static void updateDeployments(Map<String, TieredBalancer> newState, Map<String, TieredBalancer> oldState,
                                          Collection<? extends Deployment> deployments) {
        for (Deployment deployment : deployments) {
            String name = deployment.getName();

            DeploymentUpstreamProvider endpointProvider = new DeploymentUpstreamProvider(deployment);
            TieredBalancer previous = oldState.get(name);
            updateDeployment(endpointProvider, previous, newState);
        }
    }

    private static void updateDeployment(UpstreamProvider upstream, TieredBalancer previous, Map<String, TieredBalancer> newState) {
        String name = upstream.getName();
        TieredBalancer balancer;
        if (previous != null && isUpstreamsTheSame(upstream, previous)) {
            balancer = previous;
        } else {
            balancer = new TieredBalancer(name, upstream.getUpstreams());
        }
        TieredBalancer previousBalancer = newState.putIfAbsent(name, balancer);
        if (previousBalancer != null) {
            log.warn("Duplicate deployment name: {}", name);
        }
    }

    private static boolean isUpstreamsTheSame(UpstreamProvider upstreamProvider, TieredBalancer balancer) {
        return new HashSet<>(upstreamProvider.getUpstreams()).equals(new HashSet<>(balancer.getOriginalUpstreams()));
    }
}
