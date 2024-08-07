package com.epam.aidial.core.upstream;

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
import java.util.Set;

@Slf4j
public class UpstreamRouteProvider {

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

        return new UpstreamRoute(balancer, 5);
    }

    public synchronized void onUpdate(Config config) {
        log.debug("Updating load balancers state");
        Map<String, TieredBalancer> oldState = balancers;
        Map<String, TieredBalancer> newState = new HashMap<>();
        Set<String> uniqueDeployments = new HashSet<>();

        Map<String, Model> models = config.getModels();
        updateDeployments(newState, oldState, uniqueDeployments, models.values());

        Map<String, Application> applications = config.getApplications();
        updateDeployments(newState, oldState, uniqueDeployments, applications.values());

        Map<String, Addon> addons = config.getAddons();
        updateDeployments(newState, oldState, uniqueDeployments, addons.values());

        Map<String, Assistant> assistants = config.getAssistant().getAssistants();
        updateDeployments(newState, oldState, uniqueDeployments, assistants.values());

        LinkedHashMap<String, Route> routes = config.getRoutes();
        updateRoutes(newState, oldState, uniqueDeployments, routes.values());

        balancers = newState;
    }

    private static void updateRoutes(Map<String, TieredBalancer> newState, Map<String, TieredBalancer> oldState,
                                     Set<String> uniqueDeployments, Collection<Route> routes) {
        for (Route route : routes) {
            String name = route.getName();

            RouteEndpointProvider endpointProvider = new RouteEndpointProvider(route);
            TieredBalancer previous = oldState.get(name);
            updateDeployment(endpointProvider, previous, uniqueDeployments, newState);
        }
    }

    private static void updateDeployments(Map<String, TieredBalancer> newState, Map<String, TieredBalancer> oldState,
                                          Set<String> uniqueDeployments, Collection<? extends Deployment> deployments) {
        for (Deployment deployment : deployments) {
            String name = deployment.getName();

            DeploymentUpstreamProvider endpointProvider = new DeploymentUpstreamProvider(deployment);
            TieredBalancer previous = oldState.get(name);
            updateDeployment(endpointProvider, previous, uniqueDeployments, newState);
        }
    }

    private static void updateDeployment(UpstreamProvider upstream, TieredBalancer previous, Set<String> uniqueDeployments,
                                         Map<String, TieredBalancer> newState) {
        String name = upstream.getName();
        if (!uniqueDeployments.add(name)) {
            log.warn("Duplicate deployment name: {}", name);
            return;
        }
        if (previous != null && isUpstreamsTheSame(upstream, previous)) {
            newState.put(name, previous);
        } else {
            newState.put(name, new TieredBalancer(name, upstream.getUpstreams()));
        }
    }

    private static boolean isUpstreamsTheSame(UpstreamProvider upstreamProvider, TieredBalancer balancer) {
        return new HashSet<>(upstreamProvider.getUpstreams()).equals(new HashSet<>(balancer.getOriginalUpstreams()));
    }
}
