package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.data.DeploymentData;
import com.epam.aidial.core.data.FeaturesData;
import com.epam.aidial.core.data.ListData;
import com.epam.aidial.core.data.ResourceType;
import com.epam.aidial.core.service.PermissionDeniedException;
import com.epam.aidial.core.service.ResourceNotFoundException;
import com.epam.aidial.core.storage.ResourceDescription;
import com.epam.aidial.core.util.HttpStatus;
import com.epam.aidial.core.util.UrlUtil;
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

    public static Future<Deployment> selectDeployment(ProxyContext context, String id) {
        Deployment deployment = context.getConfig().selectDeployment(id);

        if (deployment != null) {
            if (!DeploymentController.hasAccess(context, deployment)) {
                return Future.failedFuture(new PermissionDeniedException("Forbidden deployment: " + id));
            } else {
                return Future.succeededFuture(deployment);
            }
        }

        Proxy proxy = context.getProxy();
        return proxy.getVertx().executeBlocking(() -> {
            String url;
            ResourceDescription resource;

            try {
                url = UrlUtil.encodePath(id);
                resource = ResourceDescription.fromAnyUrl(url, proxy.getEncryptionService());
            } catch (Throwable ignore) {
                throw new ResourceNotFoundException("Unknown application: " + id);
            }

            if (resource.isFolder() || resource.getType() != ResourceType.APPLICATION) {
                throw new ResourceNotFoundException("Invalid application url: " + url);
            }

            if (!proxy.getAccessService().hasReadAccess(resource, context)) {
                throw new PermissionDeniedException();
            }

            return proxy.getApplicationService().getApplication(resource).getValue();
        }, false);
    }

    public static boolean hasAccess(ProxyContext context, Deployment deployment) {
        Set<String> expectedUserRoles = deployment.getUserRoles();
        List<String> actualUserRoles = context.getUserRoles();

        if (expectedUserRoles == null) {
            return true;
        }

        return !expectedUserRoles.isEmpty()
                && actualUserRoles.stream().anyMatch(expectedUserRoles::contains);
    }

    private static DeploymentData createDeployment(Model model) {
        DeploymentData deployment = new DeploymentData();
        deployment.setId(model.getName());
        deployment.setModel(model.getName());
        return deployment;
    }

    static FeaturesData createFeatures(Features features) {
        FeaturesData data = new FeaturesData();

        if (features == null) {
            return data;
        }

        data.setRate(features.getRateEndpoint() != null);
        data.setTokenize(features.getTokenizeEndpoint() != null);
        data.setTruncatePrompt(features.getTruncatePromptEndpoint() != null);
        data.setConfiguration(features.getConfigurationEndpoint() != null);

        if (features.getSystemPromptSupported() != null) {
            data.setSystemPrompt(features.getSystemPromptSupported());
        }

        if (features.getToolsSupported() != null) {
            data.setTools(features.getToolsSupported());
        }

        if (features.getSeedSupported() != null) {
            data.setSeed(features.getSeedSupported());
        }

        if (features.getUrlAttachmentsSupported() != null) {
            data.setUrlAttachments(features.getUrlAttachmentsSupported());
        }

        if (features.getFolderAttachmentsSupported() != null) {
            data.setFolderAttachments(features.getFolderAttachmentsSupported());
        }

        if (features.getAllowResume() != null) {
            data.setAllowResume(features.getAllowResume());
        }

        return data;
    }
}