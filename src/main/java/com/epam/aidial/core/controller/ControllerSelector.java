package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import io.vertx.core.http.HttpMethod;
import lombok.experimental.UtilityClass;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class ControllerSelector {

    private static final Pattern PATTERN_POST_DEPLOYMENT = Pattern.compile("/+openai/deployments/([-.@a-zA-Z0-9]+)/(completions|chat/completions|embeddings)");
    private static final Pattern PATTERN_DEPLOYMENT = Pattern.compile("/+openai/deployments/([-.@a-zA-Z0-9]+)");
    private static final Pattern PATTERN_DEPLOYMENTS = Pattern.compile("/+openai/deployments");

    private static final Pattern PATTERN_MODEL = Pattern.compile("/+openai/models/([-.@a-zA-Z0-9]+)");
    private static final Pattern PATTERN_MODELS = Pattern.compile("/+openai/models");

    private static final Pattern PATTERN_ADDON = Pattern.compile("/+openai/addons/([-.@a-zA-Z0-9]+)");
    private static final Pattern PATTERN_ADDONS = Pattern.compile("/+openai/addons");

    private static final Pattern PATTERN_ASSISTANT = Pattern.compile("/+openai/assistants/([-.@a-zA-Z0-9]+)");
    private static final Pattern PATTERN_ASSISTANTS = Pattern.compile("/+openai/assistants");

    private static final Pattern PATTERN_APPLICATION = Pattern.compile("/+openai/applications/([-.@a-zA-Z0-9]+)");
    private static final Pattern PATTERN_APPLICATIONS = Pattern.compile("/+openai/applications");


    private static final Pattern PATTERN_BUCKET = Pattern.compile("/v1/bucket");

    private static final Pattern PATTERN_FILES = Pattern.compile("/v1/files/([a-zA-Z0-9]+)/(.*)");

    private static final Pattern PATTERN_FILES_METADATA = Pattern.compile("/v1/metadata/files/([a-zA-Z0-9]+)/(.*)");

    private static final Pattern PATTERN_RESOURCE = Pattern.compile("/v1/(conversations|prompts)/([a-zA-Z0-9]+)/(.*)");

    private static final Pattern PATTERN_RATE_RESPONSE = Pattern.compile("/+v1/([-.@a-zA-Z0-9]+)/rate");
    private static final Pattern PATTERN_TOKENIZE = Pattern.compile("/+v1/deployments/([-.@a-zA-Z0-9]+)/tokenize");
    private static final Pattern PATTERN_TRUNCATE_PROMPT = Pattern.compile("/+v1/deployments/([-.@a-zA-Z0-9]+)/truncate_prompt");

    public Controller select(Proxy proxy, ProxyContext context) {
        String path = context.getRequest().path();
        String decodedPath = URLDecoder.decode(path, StandardCharsets.UTF_8);
        HttpMethod method = context.getRequest().method();
        Controller controller = null;

        if (method == HttpMethod.GET) {
            controller = selectGet(proxy, context, path, decodedPath);
        } else if (method == HttpMethod.POST) {
            controller = selectPost(proxy, context, decodedPath);
        } else if (method == HttpMethod.DELETE) {
            controller = selectDelete(proxy, context, path);
        } else if (method == HttpMethod.PUT) {
            controller = selectPut(proxy, context, path);
        }

        return (controller == null) ? new RouteController(proxy, context) : controller;
    }

    private static Controller selectGet(Proxy proxy, ProxyContext context, String path, String decodedPath) {
        Matcher match;

        match = match(PATTERN_DEPLOYMENT, decodedPath);
        if (match != null) {
            DeploymentController controller = new DeploymentController(context);
            String deploymentId = match.group(1);
            return () -> controller.getDeployment(deploymentId);
        }

        match = match(PATTERN_DEPLOYMENTS, decodedPath);
        if (match != null) {
            DeploymentController controller = new DeploymentController(context);
            return controller::getDeployments;
        }

        match = match(PATTERN_MODEL, decodedPath);
        if (match != null) {
            ModelController controller = new ModelController(context);
            String modelId = match.group(1);
            return () -> controller.getModel(modelId);
        }

        match = match(PATTERN_MODELS, decodedPath);
        if (match != null) {
            ModelController controller = new ModelController(context);
            return controller::getModels;
        }

        match = match(PATTERN_ADDON, decodedPath);
        if (match != null) {
            AddonController controller = new AddonController(context);
            String addonId = match.group(1);
            return () -> controller.getAddon(addonId);
        }

        match = match(PATTERN_ADDONS, decodedPath);
        if (match != null) {
            AddonController controller = new AddonController(context);
            return controller::getAddons;
        }

        match = match(PATTERN_ASSISTANT, decodedPath);
        if (match != null) {
            AssistantController controller = new AssistantController(context);
            String assistantId = match.group(1);
            return () -> controller.getAssistant(assistantId);
        }

        match = match(PATTERN_ASSISTANTS, decodedPath);
        if (match != null) {
            AssistantController controller = new AssistantController(context);
            return controller::getAssistants;
        }

        match = match(PATTERN_APPLICATION, decodedPath);
        if (match != null) {
            ApplicationController controller = new ApplicationController(context);
            String application = match.group(1);
            return () -> controller.getApplication(application);
        }

        match = match(PATTERN_APPLICATIONS, decodedPath);
        if (match != null) {
            ApplicationController controller = new ApplicationController(context);
            return controller::getApplications;
        }

        match = match(PATTERN_FILES_METADATA, path);
        if (match != null) {
            String bucket = match.group(1);
            String filePath = match.group(2);
            FileMetadataController controller = new FileMetadataController(proxy, context);
            return () -> controller.handle("files", bucket, filePath);
        }

        match = match(PATTERN_FILES, path);
        if (match != null) {
            String bucket = match.group(1);
            String filePath = match.group(2);
            DownloadFileController controller = new DownloadFileController(proxy, context);
            return () -> controller.handle("files", bucket, filePath);
        }

        match = match(PATTERN_RESOURCE, path);
        if (match != null) {
            String folder = match.group(1);
            String bucket = match.group(2);
            String relativePath = match.group(3);
            ResourceController controller = new ResourceController(proxy, context);
            return () -> controller.handle(folder, bucket, relativePath);
        }

        match = match(PATTERN_BUCKET, decodedPath);
        if (match != null) {
            BucketController controller = new BucketController(proxy, context);
            return controller::getBucket;
        }

        return null;
    }

    private static Controller selectPost(Proxy proxy, ProxyContext context, String path) {
        Matcher match = match(PATTERN_POST_DEPLOYMENT, path);
        if (match != null) {
            String deploymentId = match.group(1);
            String deploymentApi = match.group(2);
            DeploymentPostController controller = new DeploymentPostController(proxy, context);
            return () -> controller.handle(deploymentId, deploymentApi);
        }

        match = match(PATTERN_RATE_RESPONSE, path);
        if (match != null) {
            String deploymentId = match.group(1);

            Function<Deployment, String> getter = (model) -> {
                return Optional.ofNullable(model)
                        .map(d -> d.getFeatures())
                        .map(t -> t.getRateEndpoint())
                        .orElse(null);
            };

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, false);
        }

        match = match(PATTERN_TOKENIZE, path);
        if (match != null) {
            String deploymentId = match.group(1);

            Function<Deployment, String> getter = (model) -> {
                return Optional.ofNullable(model)
                        .map(d -> d.getFeatures())
                        .map(t -> t.getTokenizeEndpoint())
                        .orElse(null);
            };

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        }

        match = match(PATTERN_TRUNCATE_PROMPT, path);
        if (match != null) {
            String deploymentId = match.group(1);

            Function<Deployment, String> getter = (model) -> {
                return Optional.ofNullable(model)
                        .map(Deployment::getFeatures)
                        .map(Features::getTruncatePromptEndpoint)
                        .orElse(null);
            };

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        }

        return null;
    }

    private static Controller selectDelete(Proxy proxy, ProxyContext context, String path) {
        Matcher match = match(PATTERN_FILES, path);
        if (match != null) {
            String bucket = match.group(1);
            String filePath = match.group(2);
            DeleteFileController controller = new DeleteFileController(proxy, context);
            return () -> controller.handle("files", bucket, filePath);
        }

        match = match(PATTERN_RESOURCE, path);
        if (match != null) {
            String folder = match.group(1);
            String bucket = match.group(2);
            String relativePath = match.group(3);
            ResourceController controller = new ResourceController(proxy, context);
            return () -> controller.handle(folder, bucket, relativePath);
        }

        return null;
    }

    private static Controller selectPut(Proxy proxy, ProxyContext context, String path) {
        Matcher match = match(PATTERN_FILES, path);
        if (match != null) {
            String bucket = match.group(1);
            String filePath = match.group(2);
            UploadFileController controller = new UploadFileController(proxy, context);
            return () -> controller.handle("files", bucket, filePath);
        }

        match = match(PATTERN_RESOURCE, path);
        if (match != null) {
            String folder = match.group(1);
            String bucket = match.group(2);
            String relativePath = match.group(3);
            ResourceController controller = new ResourceController(proxy, context);
            return () -> controller.handle(folder, bucket, relativePath);
        }

        return null;
    }

    private Matcher match(Pattern pattern, String path) {
        Matcher matcher = pattern.matcher(path);
        return matcher.find() ? matcher : null;
    }
}