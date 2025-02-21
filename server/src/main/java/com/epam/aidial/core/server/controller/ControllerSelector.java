package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.storage.util.UrlUtil;
import io.vertx.core.http.HttpMethod;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.impl.HttpServerRequestInternal;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@UtilityClass
public class ControllerSelector {

    private static final Object CONTROLLER_TEMPLATE_KEY = new Object();

    private static final List<ControllerRoute> ROUTES = new ArrayList<>();

    private static final ControllerTemplate DEFAULT_CONTROLLER_TEMPLATE = new ControllerTemplate(
            "/{path}", RouteController::new);

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
    private static final Pattern APPLICATIONS = Pattern.compile("^/v1/ops/application/(deploy|undeploy|logs|redeploy)$");

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

    private static final Pattern APP_SCHEMAS = Pattern.compile("^/v1/application_type_schemas/(schemas|schema|meta_schema)?");
    private static final Pattern CODE_INTERPRETER = Pattern.compile("^/v1/ops/code_interpreter/"
            + "(open_session|close_session|get_session|"
            + "execute_code|"
            + "upload_file|download_file|list_files|"
            + "transfer_input_file|transfer_output_file)$");

    private static final Pattern CONFIG = Pattern.compile("^/v1/ops/config/reload$");

    static {
        // GET routes
        get(PATTERN_DEPLOYMENT, (proxy, context, pathMatcher) -> {
            DeploymentController controller = new DeploymentController(context);
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getDeployment(deploymentId);
        });
        get(PATTERN_DEPLOYMENTS, (proxy, context, pathMatcher) -> {
            DeploymentController controller = new DeploymentController(context);
            return controller::getDeployments;
        });
        get(PATTERN_MODEL, (proxy, context, pathMatcher) -> {
            ModelController controller = new ModelController(context);
            String modelId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getModel(modelId);
        });
        get(PATTERN_MODELS, (proxy, context, pathMatcher) -> {
            ModelController controller = new ModelController(context);
            return controller::getModels;
        });
        get(PATTERN_ADDON, (proxy, context, pathMatcher) -> {
            AddonController controller = new AddonController(context);
            String addonId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getAddon(addonId);
        });
        get(PATTERN_ADDONS, (proxy, context, pathMatcher) -> {
            AddonController controller = new AddonController(context);
            return controller::getAddons;
        });
        get(PATTERN_ASSISTANT, (proxy, context, pathMatcher) -> {
            AssistantController controller = new AssistantController(context);
            String assistantId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getAssistant(assistantId);
        });
        get(PATTERN_ASSISTANTS, (proxy, context, pathMatcher) -> {
            AssistantController controller = new AssistantController(context);
            return controller::getAssistants;
        });
        get(PATTERN_APPLICATION, (proxy, context, pathMatcher) -> {
            ApplicationController controller = new ApplicationController(context);
            String application = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getApplication(application);
        });
        get(PATTERN_APPLICATIONS, (proxy, context, pathMatcher) -> {
            ApplicationController controller = new ApplicationController(context);
            return controller::getApplications;
        });
        get(PATTERN_FILES_METADATA, (proxy, context, pathMatcher) -> {
            FileMetadataController controller = new FileMetadataController(proxy, context);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        get(PATTERN_FILES, (proxy, context, pathMatcher) -> {
            DownloadFileController controller = new DownloadFileController(proxy, context);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        get(PATTERN_RESOURCE, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        get(PATTERN_RESOURCE_METADATA, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, true);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        get(PATTERN_BUCKET, (proxy, context, pathMatcher) -> {
            BucketController controller = new BucketController(proxy, context);
            return controller::getBucket;
        });
        get(INVITATION, (proxy, context, pathMatcher) -> {
            String invitationId = UrlUtil.decodePath(pathMatcher.group(1));
            InvitationController controller = new InvitationController(proxy, context);
            return () -> controller.getOrAcceptInvitation(invitationId);
        });
        get(INVITATIONS, (proxy, context, pathMatcher) -> {
            InvitationController controller = new InvitationController(proxy, context);
            return controller::getInvitations;
        });
        get(DEPLOYMENT_LIMITS, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            LimitController controller = new LimitController(proxy, context);
            return () -> controller.getLimits(deploymentId);
        });
        get(PATTERN_CONFIGURATION, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getConfigurationEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, false);
        });
        get(USER_INFO, (proxy, context, pathMatcher) -> new UserInfoController(context));
        get(APP_SCHEMAS, (proxy, context, pathMatcher) -> {
            ApplicationTypeSchemaController controller = new ApplicationTypeSchemaController(context);
            String operation = pathMatcher.group(1);
            return switch (operation) {
                case "schemas" -> controller::handleListSchemas;
                case "meta_schema" -> controller::handleGetMetaSchema;
                case "schema" -> controller::handleGetSchema;
                default -> null;
            };
        });

        // POST routes
        post(PATTERN_POST_DEPLOYMENT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            String deploymentApi = UrlUtil.decodePath(pathMatcher.group(2));
            DeploymentPostController controller = new DeploymentPostController(proxy, context);
            return () -> controller.handle(deploymentId, deploymentApi);
        });
        post(PATTERN_RATE_RESPONSE, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getRateEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, false);
        });
        post(PATTERN_TOKENIZE, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getTokenizeEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        });
        post(PATTERN_TRUNCATE_PROMPT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getTruncatePromptEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        });
        post(SHARE_RESOURCE_OPERATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ShareController.Operation op = ShareController.Operation.valueOf(operation.toUpperCase());

            ShareController controller = new ShareController(proxy, context);
            return () -> controller.handle(op);
        });
        post(PUBLICATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
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
        });
        post(PUBLICATION_RULES, (proxy, context, pathMatcher) -> {
            PublicationController controller = new PublicationController(proxy, context);
            return controller::listRules;
        });
        post(RESOURCE_OPERATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ResourceOperationController controller = new ResourceOperationController(proxy, context);

            return switch (operation) {
                case "move" -> controller::move;
                case "subscribe" -> controller::subscribe;
                default -> null;
            };
        });
        post(PUBLISHED_RESOURCES, (proxy, context, pathMatcher) -> {
            PublicationController controller = new PublicationController(proxy, context);
            return controller::listPublishedResources;
        });
        post(NOTIFICATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            NotificationController controller = new NotificationController(proxy, context);

            return switch (operation) {
                case "list" -> controller::listNotifications;
                case "delete" -> controller::deleteNotification;
                default -> null;
            };
        });
        post(APPLICATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ApplicationController controller = new ApplicationController(context);

            return switch (operation) {
                case "deploy" -> controller::deployApplication;
                case "undeploy" -> controller::undeployApplication;
                case "logs" -> controller::getApplicationLogs;
                case "redeploy" -> controller::redeployApplication;
                default -> null;
            };
        });
        post(CODE_INTERPRETER, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            CodeInterpreterController controller = new CodeInterpreterController(context);

            return switch (operation) {
                case "open_session" -> controller::openSession;
                case "close_session" -> controller::closeSession;
                case "get_session" -> controller::getSession;
                case "execute_code" -> controller::executeCode;
                case "upload_file" -> controller::uploadFile;
                case "download_file" -> controller::downloadFile;
                case "list_files" -> controller::listFiles;
                case "transfer_input_file" -> controller::transferInputFile;
                case "transfer_output_file" -> controller::transferOutputFile;
                default -> null;
            };
        });
        post(CONFIG, (proxy, context, pathMatcher) -> new ConfigController(context));
        // DELETE routes
        delete(PATTERN_FILES, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        delete(PATTERN_RESOURCE, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        delete(INVITATION, (proxy, context, pathMatcher) -> {
            String invitationId = UrlUtil.decodePath(pathMatcher.group(1));
            InvitationController controller = new InvitationController(proxy, context);
            return () -> controller.deleteInvitation(invitationId);
        });
        // PUT routes
        put(PATTERN_FILES, (proxy, context, pathMatcher) -> {
            UploadFileController controller = new UploadFileController(proxy, context);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        put(PATTERN_RESOURCE, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
    }

    public ControllerTemplate select(HttpServerRequest request) {
        HttpMethod method = request.method();
        String path = request.path();
        if (request instanceof HttpServerRequestInternal req) {
            ControllerTemplate selection = req.context().getLocal(CONTROLLER_TEMPLATE_KEY);
            if (selection == null) {
                selection = select(method, path);
                req.context().putLocal(CONTROLLER_TEMPLATE_KEY, selection);
            }
            return selection;
        }
        return select(method, path);
    }

    private ControllerTemplate select(HttpMethod method, String path) {
        return ROUTES.stream()
                .map(r -> r.select(method, path))
                .filter(Objects::nonNull)
                .findAny()
                .orElse(DEFAULT_CONTROLLER_TEMPLATE);
    }

    private void get(Pattern pathPattern, ControllerRoute.Initializer initializer) {
        ROUTES.add(new ControllerRoute(HttpMethod.GET, pathPattern, initializer));
    }

    private void post(Pattern pathPattern, ControllerRoute.Initializer initializer) {
        ROUTES.add(new ControllerRoute(HttpMethod.POST, pathPattern, initializer));
    }

    private void put(Pattern pathPattern, ControllerRoute.Initializer initializer) {
        ROUTES.add(new ControllerRoute(HttpMethod.PUT, pathPattern, initializer));
    }

    private void delete(Pattern pathPattern, ControllerRoute.Initializer initializer) {
        ROUTES.add(new ControllerRoute(HttpMethod.DELETE, pathPattern, initializer));
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

    private record ControllerRoute(HttpMethod method, Pattern pathPattern, Initializer initializer) {
        public ControllerTemplate select(HttpMethod method, String path) {
            if (this.method.equals(method)) {
                Matcher matcher = this.pathPattern.matcher(path);
                if (matcher.find()) {
                    String pathTemplate = RegexUtil.replaceNamedGroups(this.pathPattern, path);
                    return new ControllerTemplate(pathTemplate,
                            (proxy, context) -> this.initializer.init(proxy, context, matcher));
                }
            }
            return null;
        }

        private interface Initializer {
            Controller init(Proxy proxy, ProxyContext context, Matcher pathMatcher);
        }
    }
}
