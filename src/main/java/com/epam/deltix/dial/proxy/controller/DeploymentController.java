package com.epam.deltix.dial.proxy.controller;

import com.epam.deltix.dial.proxy.ProxyContext;
import com.epam.deltix.dial.proxy.config.*;
import com.epam.deltix.dial.proxy.data.DeploymentData;
import com.epam.deltix.dial.proxy.data.ListData;
import com.epam.deltix.dial.proxy.util.HttpStatus;
import io.vertx.core.Future;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RequiredArgsConstructor
public class DeploymentController {

    private final ProxyContext context;

    public Future<?> getDeployment(String deploymentId) {
        Config config = context.getConfig();
        Model model = config.getModels().get(deploymentId);

        if (model == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!DeploymentController.hasAccess(context, model)) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        DeploymentData data = createDeployment(model);
        return context.respond(HttpStatus.OK, data);
    }

    public Future<?> getDeployments() {
        Config config = context.getConfig();
        List<DeploymentData> deployments = new ArrayList<>();

        for (Model model : config.getModels().values()) {
            if (hasAccess(context, model)) {
                DeploymentData deployment = createDeployment(model);
                deployments.add(deployment);
            }
        }

        ListData<DeploymentData> list = new ListData<>();
        list.setData(deployments);

        return context.respond(HttpStatus.OK, list);
    }

    public static boolean hasAccess(ProxyContext context, Deployment deployment) {
        return hasAssessByLimits(context, deployment) && hasAccessByUserRoles(context, deployment);
    }

    public static boolean hasAssessByLimits(ProxyContext context, Deployment deployment) {
        Key key = context.getKey();
        Role keyRole = context.getConfig().getRoles().get(key.getRole());
        if (keyRole == null) {
            return false;
        }

        Limit keyLimit = keyRole.getLimits().get(deployment.getName());
        if (keyLimit == null || !keyLimit.isPositive()) {
            return false;
        }

        return true;
    }

    public static boolean hasAccessByUserRoles(ProxyContext context, Deployment deployment) {
        Key key = context.getKey();
        Set<String> expectedUserRoles = deployment.getUserRoles();
        List<String> actualUserRoles = context.getUserRoles();

        if (actualUserRoles == null) {
            return key.getUserAuth() != UserAuth.ENABLED;
        }

        if (expectedUserRoles.isEmpty()) {
            return true;
        }

        return actualUserRoles.stream().anyMatch(expectedUserRoles::contains);
    }

    private static DeploymentData createDeployment(Model model) {
        DeploymentData deployment = new DeploymentData();
        deployment.setId(model.getName());
        deployment.setModel(model.getName());
        return deployment;
    }
}