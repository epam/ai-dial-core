package com.epam.aidial.core.server.config;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Interceptor;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.Role;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.server.security.ApiKeyStore;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.Nullable;

/**
 * Post-processes a freshly-loaded {@link Config}: route ordering, deployment-id uniqueness,
 * entity-name back-fill, ToolSet name validation. Extracted from {@code FileConfigStore.load()}
 * so collaborators ({@code MergedConfigStore} in slice 2S.8) can reuse the same processing
 * pipeline. This is the structural pass — always fatal-to-entity. The semantic pass and the
 * skip/abort knob land in slice 2S.9.
 */
@Slf4j
public final class ConfigPostProcessor {

    private ConfigPostProcessor() {
    }

    public static void process(Config config, @Nullable ApiKeyStore apiKeyStore) {
        Set<String> deploymentIds = new HashSet<>();

        sortRoutes(config);
        processModels(config, deploymentIds);
        processApplications(config, deploymentIds);
        processRoles(config);
        processInterceptors(config, deploymentIds);
        processToolSets(config, deploymentIds);

        if (apiKeyStore != null) {
            apiKeyStore.addProjectKeys(config.getKeys());
        }
    }

    private static void sortRoutes(Config config) {
        List<Route> sortedRoutes = new ArrayList<>();
        for (Map.Entry<String, Route> entry : config.getRoutes().entrySet()) {
            String name = entry.getKey();
            Route route = entry.getValue();
            route.setName(name);
            log.debug("Loading {}", route);
            sortedRoutes.add(route);
        }
        sortedRoutes.sort(Comparator.comparingInt(Route::getOrder));
        LinkedHashMap<String, Route> routes = config.getRoutes();
        routes.clear();
        for (Route route : sortedRoutes) {
            routes.put(route.getName(), route);
        }
    }

    private static void processModels(Config config, Set<String> deploymentIds) {
        for (Map.Entry<String, Model> entry : config.getModels().entrySet()) {
            String name = entry.getKey();
            enforceDeploymentUniqueness(name, deploymentIds);
            Model model = entry.getValue();
            model.setName(name);
            log.debug("Loading {}", model);
        }
    }

    private static void processApplications(Config config, Set<String> deploymentIds) {
        for (Map.Entry<String, Application> entry : config.getApplications().entrySet()) {
            String name = entry.getKey();
            enforceDeploymentUniqueness(name, deploymentIds);
            Application application = entry.getValue();
            application.setName(name);
            log.debug("Loading {}", application);
        }
    }

    private static void processRoles(Config config) {
        for (Map.Entry<String, Role> entry : config.getRoles().entrySet()) {
            String name = entry.getKey();
            Role role = entry.getValue();
            role.setName(name);
            log.debug("Start loading role `{}`", role.getName());
            for (Map.Entry<String, Limit> limitEntry : role.getLimits().entrySet()) {
                log.debug("Loading {} for deployment `{}`", limitEntry.getValue(), limitEntry.getKey());
            }
            log.debug("End loading role `{}`", role.getName());
        }
    }

    private static void processInterceptors(Config config, Set<String> deploymentIds) {
        for (Map.Entry<String, Interceptor> entry : config.getInterceptors().entrySet()) {
            String name = entry.getKey();
            enforceDeploymentUniqueness(name, deploymentIds);
            Interceptor interceptor = entry.getValue();
            interceptor.setName(name);
            log.debug("Loading {}", interceptor);
        }
    }

    private static void processToolSets(Config config, Set<String> deploymentIds) {
        Iterator<Map.Entry<String, ToolSet>> iterator = config.getToolsets().entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, ToolSet> entry = iterator.next();
            String name = entry.getKey();
            enforceDeploymentUniqueness(name, deploymentIds);
            if (isValidResourceKey(name)) {
                ToolSet toolSet = entry.getValue();
                toolSet.setName(name);
                log.debug("Loading {}", entry.getValue());
            } else {
                log.warn("Invalid ToolSet name: {}", name);
                iterator.remove();
            }
        }
    }

    private static void enforceDeploymentUniqueness(String deploymentId, Set<String> deployments) {
        if (!deployments.add(deploymentId)) {
            throw new IllegalStateException("Deployment uniqueness is violated: duplicate is found " + deploymentId);
        }
    }

    private static boolean isValidResourceKey(String resourceKey) {
        return resourceKey.matches("^[A-Za-z0-9-_]+$");
    }
}
