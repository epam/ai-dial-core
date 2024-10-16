package com.epam.aidial.core.controller;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.util.SpanUtil;
import com.epam.aidial.core.util.UrlUtil;
import io.vertx.core.http.HttpMethod;
import lombok.experimental.UtilityClass;

import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class ControllerSelector {

    private static final Pattern PATTERN_POST_DEPLOYMENT = Pattern.compile("^/+openai/deployments/(?<id>.+?)/(completions|chat/completions|embeddings)$");
    private static final Pattern PATTERN_DEPLOYMENT = Pattern.compile("^/+openai/deployments/(?<id>.+?)$");
    private static final Pattern PATTERN_DEPLOYMENTS = Pattern.compile("^/+openai/deployments$");

    private static final Pattern PATTERN_MODEL = Pattern.compile("^/+openai/models/(?<id>.+?)$");
    private static final Pattern PATTERN_MODELS = Pattern.compile("^/+openai/models$");

    private static final Pattern PATTERN_ADDON = Pattern.compile("^/+openai/addons/(?<id>.+?)$");
    private static final Pattern PATTERN_ADDONS = Pattern.compile("^/+openai/addons$");

    private static final Pattern PATTERN_ASSISTANT = Pattern.compile("^/+openai/assistants/(?<id>.+?)$");
    private static final Pattern PATTERN_ASSISTANTS = Pattern.compile("^/+openai/assistants$");

    private static final Pattern PATTERN_APPLICATION = Pattern.compile("^/+openai/applications/(?<id>.+?)$");
    private static final Pattern PATTERN_APPLICATIONS = Pattern.compile("^/+openai/applications$");
    private static final Pattern APPLICATIONS = Pattern.compile("^/v1/ops/application/(start|stop|logs)$");

    private static final Pattern PATTERN_BUCKET = Pattern.compile("^/v1/bucket$");

    private static final Pattern PATTERN_FILES = Pattern.compile("^/v1/files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)");
    private static final Pattern PATTERN_FILES_METADATA = Pattern.compile("^/v1/metadata/files/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)");

    private static final Pattern PATTERN_RESOURCE = Pattern.compile("^/v1/(conversations|prompts|applications)/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)");
    private static final Pattern PATTERN_RESOURCE_METADATA = Pattern.compile("^/v1/metadata/(conversations|prompts|applications)/(?<bucket>[a-zA-Z0-9]+)/(?<path>.*)");

    // deployment feature patterns
    private static final Pattern PATTERN_RATE_RESPONSE = Pattern.compile("^/+v1/(?<id>.+?)/rate$");
    private static final Pattern PATTERN_TOKENIZE = Pattern.compile("^/+v1/deployments/(?<id>.+?)/tokenize$");
    private static final Pattern PATTERN_TRUNCATE_PROMPT = Pattern.compile("^/+v1/deployments/(?<id>.+?)/truncate_prompt$");
    private static final Pattern PATTERN_CONFIGURATION = Pattern.compile("^/+v1/deployments/(?<id>.+?)/configuration$");

    private static final Pattern SHARE_RESOURCE_OPERATIONS = Pattern.compile("^/v1/ops/resource/share/(create|list|discard|revoke|copy)$");
    private static final Pattern INVITATIONS = Pattern.compile("^/v1/invitations$");
    private static final Pattern INVITATION = Pattern.compile("^/v1/invitations/(?<id>[a-zA-Z0-9]+)$");
    private static final Pattern PUBLICATIONS = Pattern.compile("^/v1/ops/publication/(list|get|create|delete|approve|reject)$");
    private static final Pattern PUBLISHED_RESOURCES = Pattern.compile("^/v1/ops/publication/resource/list$");
    private static final Pattern PUBLICATION_RULES = Pattern.compile("^/v1/ops/publication/rule/list$");

    private static final Pattern RESOURCE_OPERATIONS = Pattern.compile("^/v1/ops/resource/(move|subscribe)$");

    private static final Pattern DEPLOYMENT_LIMITS = Pattern.compile("^/v1/deployments/(?<id>.+?)/limits$");

    private static final Pattern NOTIFICATIONS = Pattern.compile("^/v1/ops/notification/(list|delete)$");

    private static final Pattern USER_INFO = Pattern.compile("^/v1/user/info$");

    public Controller select(Proxy proxy, ProxyContext context) {
        String path = context.getRequest().path();
        HttpMethod method = context.getRequest().method();
        Controller controller = null;

        if (method == HttpMethod.GET) {
            controller = selectGet(proxy, context, path);
        } else if (method == HttpMethod.POST) {
            controller = selectPost(proxy, context, path);
        } else if (method == HttpMethod.DELETE) {
            controller = selectDelete(proxy, context, path);
        } else if (method == HttpMethod.PUT) {
            controller = selectPut(proxy, context, path);
        }

        return (controller == null) ? new RouteController(proxy, context) : controller;
    }

    private static Controller selectGet(Proxy proxy, ProxyContext context, String path) {
        Matcher match;

        match = match(PATTERN_DEPLOYMENT, path, context);
        if (match != null) {
            DeploymentController controller = new DeploymentController(context);
            String deploymentId = UrlUtil.decodePath(match.group(1));
            return () -> controller.getDeployment(deploymentId);
        }

        match = match(PATTERN_DEPLOYMENTS, path, context);
        if (match != null) {
            DeploymentController controller = new DeploymentController(context);
            return controller::getDeployments;
        }

        match = match(PATTERN_MODEL, path, context);
        if (match != null) {
            ModelController controller = new ModelController(context);
            String modelId = UrlUtil.decodePath(match.group(1));
            return () -> controller.getModel(modelId);
        }

        match = match(PATTERN_MODELS, path, context);
        if (match != null) {
            ModelController controller = new ModelController(context);
            return controller::getModels;
        }

        match = match(PATTERN_ADDON, path, context);
        if (match != null) {
            AddonController controller = new AddonController(context);
            String addonId = UrlUtil.decodePath(match.group(1));
            return () -> controller.getAddon(addonId);
        }

        match = match(PATTERN_ADDONS, path, context);
        if (match != null) {
            AddonController controller = new AddonController(context);
            return controller::getAddons;
        }

        match = match(PATTERN_ASSISTANT, path, context);
        if (match != null) {
            AssistantController controller = new AssistantController(context);
            String assistantId = UrlUtil.decodePath(match.group(1));
            return () -> controller.getAssistant(assistantId);
        }

        match = match(PATTERN_ASSISTANTS, path, context);
        if (match != null) {
            AssistantController controller = new AssistantController(context);
            return controller::getAssistants;
        }

        match = match(PATTERN_APPLICATION, path, context);
        if (match != null) {
            ApplicationController controller = new ApplicationController(context);
            String application = UrlUtil.decodePath(match.group(1));
            return () -> controller.getApplication(application);
        }

        match = match(PATTERN_APPLICATIONS, path, context);
        if (match != null) {
            ApplicationController controller = new ApplicationController(context);
            return controller::getApplications;
        }

        match = match(PATTERN_FILES_METADATA, path, context);
        if (match != null) {
            FileMetadataController controller = new FileMetadataController(proxy, context);
            return () -> controller.handle(resourcePath(path));
        }

        match = match(PATTERN_FILES, path, context);
        if (match != null) {
            DownloadFileController controller = new DownloadFileController(proxy, context);
            return () -> controller.handle(resourcePath(path));
        }

        match = match(PATTERN_RESOURCE, path, context);
        if (match != null) {
            ResourceController controller = new ResourceController(proxy, context, false);
            return () -> controller.handle(resourcePath(path));
        }

        match = match(PATTERN_RESOURCE_METADATA, path, context);
        if (match != null) {
            ResourceController controller = new ResourceController(proxy, context, true);
            return () -> controller.handle(resourcePath(path));
        }

        match = match(PATTERN_BUCKET, path, context);
        if (match != null) {
            BucketController controller = new BucketController(proxy, context);
            return controller::getBucket;
        }

        match = match(INVITATION, path, context);
        if (match != null) {
            String invitationId = UrlUtil.decodePath(match.group(1));
            InvitationController controller = new InvitationController(proxy, context);
            return () -> controller.getOrAcceptInvitation(invitationId);
        }

        match = match(INVITATIONS, path, context);
        if (match != null) {
            InvitationController controller = new InvitationController(proxy, context);
            return controller::getInvitations;
        }

        match = match(DEPLOYMENT_LIMITS, path, context);
        if (match != null) {
            String deploymentId = UrlUtil.decodePath(match.group(1));
            LimitController controller = new LimitController(proxy, context);
            return () -> controller.getLimits(deploymentId);
        }

        match = match(PATTERN_CONFIGURATION, path, context);
        if (match != null) {
            String deploymentId = UrlUtil.decodePath(match.group(1));
            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getConfigurationEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, false);
        }

        match = match(USER_INFO, path, context);
        if (match != null) {
            return new UserInfoController(context);
        }

        return null;
    }

    private static Controller selectPost(Proxy proxy, ProxyContext context, String path) {
        Matcher match = match(PATTERN_POST_DEPLOYMENT, path, context);
        if (match != null) {
            String deploymentId = UrlUtil.decodePath(match.group(1));
            String deploymentApi = UrlUtil.decodePath(match.group(2));
            DeploymentPostController controller = new DeploymentPostController(proxy, context);
            return () -> controller.handle(deploymentId, deploymentApi);
        }

        match = match(PATTERN_RATE_RESPONSE, path, context);
        if (match != null) {
            String deploymentId = UrlUtil.decodePath(match.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getRateEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, false);
        }

        match = match(PATTERN_TOKENIZE, path, context);
        if (match != null) {
            String deploymentId = UrlUtil.decodePath(match.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getTokenizeEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        }

        match = match(PATTERN_TRUNCATE_PROMPT, path, context);
        if (match != null) {
            String deploymentId = UrlUtil.decodePath(match.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getTruncatePromptEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        }

        match = match(SHARE_RESOURCE_OPERATIONS, path, context);
        if (match != null) {
            String operation = match.group(1);
            ShareController.Operation op = ShareController.Operation.valueOf(operation.toUpperCase());

            ShareController controller = new ShareController(proxy, context);
            return () -> controller.handle(op);
        }

        match = match(PUBLICATIONS, path, context);
        if (match != null) {
            String operation = match.group(1);
            PublicationController controller = new PublicationController(proxy, context);

            return switch (operation) {
                case "list" -> controller::listPublications;
                case "get" -> controller::getPublication;
                case "create" -> controller::createPublication;
                case "delete" -> controller::deletePublication;
                case "approve" -> controller::approvePublication;
                case "reject" -> controller::rejectPublication;
                default -> null;
            };
        }

        match = match(PUBLICATION_RULES, path, context);
        if (match != null) {
            PublicationController controller = new PublicationController(proxy, context);
            return controller::listRules;
        }

        match = match(RESOURCE_OPERATIONS, path, context);
        if (match != null) {
            String operation = match.group(1);
            ResourceOperationController controller = new ResourceOperationController(proxy, context);

            return switch (operation) {
                case "move" -> controller::move;
                case "subscribe" -> controller::subscribe;
                default -> null;
            };
        }

        match = match(PUBLISHED_RESOURCES, path, context);
        if (match != null) {
            PublicationController controller = new PublicationController(proxy, context);
            return controller::listPublishedResources;
        }

        match = match(NOTIFICATIONS, path, context);
        if (match != null) {
            String operation = match.group(1);
            NotificationController controller = new NotificationController(proxy, context);

            return switch (operation) {
                case "list" -> controller::listNotifications;
                case "delete" -> controller::deleteNotification;
                default -> null;
            };
        }

        match = match(APPLICATIONS, path, context);
        if (match != null) {
            String operation = match.group(1);
            ApplicationController controller = new ApplicationController(context);

            return switch (operation) {
                case "start" -> controller::startApplication;
                case "stop" -> controller::stopApplication;
                case "logs" -> controller::getApplicationLogs;
                default -> null;
            };
        }

        return null;
    }

    private static Controller selectDelete(Proxy proxy, ProxyContext context, String path) {
        Matcher match = match(PATTERN_FILES, path, context);
        if (match != null) {
            DeleteFileController controller = new DeleteFileController(proxy, context);
            return () -> controller.handle(resourcePath(path));
        }

        match = match(PATTERN_RESOURCE, path, context);
        if (match != null) {
            ResourceController controller = new ResourceController(proxy, context, false);
            return () -> controller.handle(resourcePath(path));
        }

        match = match(INVITATION, path, context);
        if (match != null) {
            String invitationId = UrlUtil.decodePath(match.group(1));
            InvitationController controller = new InvitationController(proxy, context);
            return () -> controller.deleteInvitation(invitationId);
        }

        return null;
    }

    private static Controller selectPut(Proxy proxy, ProxyContext context, String path) {
        Matcher match = match(PATTERN_FILES, path, context);
        if (match != null) {
            UploadFileController controller = new UploadFileController(proxy, context);
            return () -> controller.handle(resourcePath(path));
        }

        match = match(PATTERN_RESOURCE, path, context);
        if (match != null) {
            ResourceController controller = new ResourceController(proxy, context, false);
            return () -> controller.handle(resourcePath(path));
        }

        return null;
    }

    private Matcher match(Pattern pattern, String path, ProxyContext context) {
        Matcher matcher = pattern.matcher(path);
        if (matcher.find()) {
            SpanUtil.updateName(pattern, path, context.getRequest().method().name());
            return matcher;
        }
        return null;
    }

    private String resourcePath(String url) {
        String prefix = "/v1/";

        if (!url.startsWith(prefix)) {
            throw new IllegalArgumentException("Resource url must start with /v1/: " + url);
        }

        if (url.startsWith("/v1/metadata/")) {
            prefix = "/v1/metadata/";
        }

        return url.substring(prefix.length());
    }
}