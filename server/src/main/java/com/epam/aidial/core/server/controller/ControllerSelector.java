package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Features;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.config.MergedConfigStore;
import com.epam.aidial.core.server.controller.anthropic.AnthropicModelController;
import com.epam.aidial.core.server.controller.anthropic.MessagesController;
import com.epam.aidial.core.server.controller.anthropic.MessagesCountTokensController;
import com.epam.aidial.core.server.controller.route.ApplicationRouteController;
import com.epam.aidial.core.server.controller.route.GlobalRouteController;
import com.epam.aidial.core.server.data.RouteTemplate;
import com.epam.aidial.core.server.security.AdminRoleAuthorizationService;
import com.epam.aidial.core.server.security.ConfigAuthorizationService;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
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
            "/{path}", GlobalRouteController::new);

    static {
        // External-service definition management. Registered before the generic RESOURCE route so
        // /v1/applications/{appId}/external-services[/{id}] is not swallowed by it (first match wins).
        get(RouteTemplate.EXTERNAL_SERVICES_MANAGEMENT, (proxy, context, pathMatcher) -> {
            String appId = UrlUtil.decodePath(pathMatcher.group("appId"));
            ExternalServiceManagementController controller = new ExternalServiceManagementController(proxy, context);
            return () -> controller.listExternalServices(appId);
        });
        get(RouteTemplate.EXTERNAL_SERVICE_MANAGEMENT, (proxy, context, pathMatcher) -> {
            String appId = UrlUtil.decodePath(pathMatcher.group("appId"));
            String serviceId = UrlUtil.decodePath(pathMatcher.group("id"));
            ExternalServiceManagementController controller = new ExternalServiceManagementController(proxy, context);
            return () -> controller.getExternalService(appId, serviceId);
        });
        put(RouteTemplate.EXTERNAL_SERVICE_MANAGEMENT, (proxy, context, pathMatcher) -> {
            String appId = UrlUtil.decodePath(pathMatcher.group("appId"));
            String serviceId = UrlUtil.decodePath(pathMatcher.group("id"));
            ExternalServiceManagementController controller = new ExternalServiceManagementController(proxy, context);
            return () -> controller.putExternalService(appId, serviceId);
        });
        delete(RouteTemplate.EXTERNAL_SERVICE_MANAGEMENT, (proxy, context, pathMatcher) -> {
            String appId = UrlUtil.decodePath(pathMatcher.group("appId"));
            String serviceId = UrlUtil.decodePath(pathMatcher.group("id"));
            ExternalServiceManagementController controller = new ExternalServiceManagementController(proxy, context);
            return () -> controller.deleteExternalService(appId, serviceId);
        });
        post(RouteTemplate.EXTERNAL_SERVICE_CONSENT, (proxy, context, pathMatcher) -> {
            String appId = UrlUtil.decodePath(pathMatcher.group("appId"));
            String serviceId = UrlUtil.decodePath(pathMatcher.group("id"));
            ExternalServiceManagementController controller = new ExternalServiceManagementController(proxy, context);
            return () -> controller.grantConsent(appId, serviceId);
        });
        delete(RouteTemplate.EXTERNAL_SERVICE_CONSENT, (proxy, context, pathMatcher) -> {
            String appId = UrlUtil.decodePath(pathMatcher.group("appId"));
            String serviceId = UrlUtil.decodePath(pathMatcher.group("id"));
            ExternalServiceManagementController controller = new ExternalServiceManagementController(proxy, context);
            return () -> controller.withdrawConsent(appId, serviceId);
        });

        // API-managed applications/toolsets in the platform bucket. Registered before the generic
        // RESOURCE/RESOURCE_METADATA routes so /v1/(applications|toolsets)/platform/... is not
        // swallowed by them (first match wins) and instead reaches ConfigResourceController.
        get(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE, ControllerSelector::configResourceController);
        get(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);
        post(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE, ControllerSelector::configResourceController);
        post(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);
        put(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE, ControllerSelector::configResourceController);
        put(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);
        delete(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE, ControllerSelector::configResourceController);
        delete(RouteTemplate.PLATFORM_APP_TOOLSET_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);

        // GET routes
        get(RouteTemplate.DEPLOYMENT, (proxy, context, pathMatcher) -> {
            DeploymentController controller = new DeploymentController(proxy, context);
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getDeployment(deploymentId);
        });
        get(RouteTemplate.DEPLOYMENTS, (proxy, context, pathMatcher) -> {
            DeploymentController controller = new DeploymentController(proxy, context);
            return controller::getDeployments;
        });
        get(RouteTemplate.MODEL, (proxy, context, pathMatcher) -> {
            ModelController controller = new ModelController(context);
            String modelId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getModel(modelId);
        });
        get(RouteTemplate.MODELS, (proxy, context, pathMatcher) -> {
            ModelController controller = new ModelController(context);
            return controller::getModels;
        });
        get(RouteTemplate.LLM_ANTHROPIC_MODEL, (proxy, context, pathMatcher) -> {
            AnthropicModelController controller = new AnthropicModelController(context);
            String modelId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getModel(modelId);
        });
        get(RouteTemplate.LLM_ANTHROPIC_MODELS, (proxy, context, pathMatcher) -> {
            AnthropicModelController controller = new AnthropicModelController(context);
            return controller::getModels;
        });
        get(RouteTemplate.APPLICATION, (proxy, context, pathMatcher) -> {
            ApplicationController controller = new ApplicationController(context);
            String application = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getApplication(application);
        });
        get(RouteTemplate.APPLICATIONS, (proxy, context, pathMatcher) -> {
            ApplicationController controller = new ApplicationController(context);
            return controller::getApplications;
        });
        get(RouteTemplate.FILES_METADATA, (proxy, context, pathMatcher) -> {
            FileMetadataController controller = new FileMetadataController(proxy, context);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        get(RouteTemplate.FILES, (proxy, context, pathMatcher) -> {
            DownloadFileController controller = new DownloadFileController(proxy, context);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        get(RouteTemplate.RESOURCE, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        get(RouteTemplate.RESOURCE_METADATA, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, true);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        // Metadata routes: the file-listing route is more specific and must precede the children route.
        get(RouteTemplate.COMPLEX_RESOURCE_FILE_METADATA, (proxy, context, pathMatcher) -> {
            String filePath = pathMatcher.group("filePath");
            String subPath = filePath == null ? null : UrlUtil.decodePath(filePath);
            ComplexResourceMetadataController controller = new ComplexResourceMetadataController(proxy, context, true, subPath);
            return () -> controller.handle(complexResourceUrl(pathMatcher));
        });
        get(RouteTemplate.COMPLEX_RESOURCE_METADATA, (proxy, context, pathMatcher) -> {
            ComplexResourceMetadataController controller = new ComplexResourceMetadataController(proxy, context, false, null);
            return () -> controller.handle(complexResourceFolderUrl(pathMatcher));
        });
        get(RouteTemplate.RESOURCE_FOLDER, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, false, true);
            return () -> controller.handle(complexResourceFolderUrl(pathMatcher));
        });
        get(RouteTemplate.COMPLEX_RESOURCE, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePathV2(path));
        });
        get(RouteTemplate.COMPLEX_RESOURCE_FILE, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, false,
                    UrlUtil.decodePath(pathMatcher.group("filePath")));
            return () -> controller.handle(complexResourceUrl(pathMatcher));
        });
        get(RouteTemplate.CONFIG_RESOURCE, ControllerSelector::configResourceController);
        get(RouteTemplate.CONFIG_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);
        // Wire POST/PUT/DELETE so the controller can emit a proper 405 + Allow header (RFC 7231)
        // instead of the router-level 404 for verbs the metadata surface does not implement.
        post(RouteTemplate.CONFIG_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);
        put(RouteTemplate.CONFIG_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);
        delete(RouteTemplate.CONFIG_RESOURCE_METADATA, ControllerSelector::configResourceMetadataController);
        get(RouteTemplate.CONFIG_HEALTH, (proxy, context, pathMatcher) -> {
            ConfigAuthorizationService authService = new AdminRoleAuthorizationService(proxy.getAccessService());
            AdminHealthConfigController controller = new AdminHealthConfigController(
                    context, authService, (MergedConfigStore) proxy.getConfigStore());
            return controller::handle;
        });
        ControllerRoute.Initializer fileConfigInitializer = (proxy, context, pathMatcher) -> {
            ConfigAuthorizationService authService = new AdminRoleAuthorizationService(proxy.getAccessService());
            MergedConfigStore mergedConfigStore = (MergedConfigStore) proxy.getConfigStore();
            String type = pathMatcher.group("type");
            String name = pathMatcher.group("name");
            String decodedName = name == null ? null : UrlUtil.decodePath(name);
            FileConfigController controller = new FileConfigController(
                    context, authService, mergedConfigStore, type, decodedName);
            return controller::handle;
        };
        get(RouteTemplate.ADMIN_FILE_CONFIG, fileConfigInitializer);
        // Wire POST/PUT/DELETE so the controller can emit 405 with Allow: GET (RFC 9110 §15.5.6)
        // instead of falling through to GlobalRouteController, which could match an unintended
        // configured route in production.
        post(RouteTemplate.ADMIN_FILE_CONFIG, fileConfigInitializer);
        put(RouteTemplate.ADMIN_FILE_CONFIG, fileConfigInitializer);
        delete(RouteTemplate.ADMIN_FILE_CONFIG, fileConfigInitializer);
        get(RouteTemplate.BUCKET, (proxy, context, pathMatcher) -> {
            BucketController controller = new BucketController(proxy, context);
            return controller::getBucket;
        });
        get(RouteTemplate.INVITATION, (proxy, context, pathMatcher) -> {
            String invitationId = UrlUtil.decodePath(pathMatcher.group(1));
            InvitationController controller = new InvitationController(proxy, context);
            return () -> controller.getOrAcceptInvitation(invitationId);
        });
        get(RouteTemplate.INVITATIONS, (proxy, context, pathMatcher) -> {
            InvitationController controller = new InvitationController(proxy, context);
            return controller::getInvitations;
        });
        get(RouteTemplate.DEPLOYMENT_LIMITS, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            LimitController controller = new LimitController(proxy, context);
            return () -> controller.getDeploymentLimits(deploymentId);
        });
        get(RouteTemplate.CONFIGURATION, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getConfigurationEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, false);
        });
        get(RouteTemplate.USER_INFO, (proxy, context, pathMatcher) -> new UserInfoController(context));
        get(RouteTemplate.USER_LIMITS, (proxy, context, pathMatcher) -> {
            LimitController controller = new LimitController(proxy, context);
            return controller::getUserLimits;
        });
        get(RouteTemplate.USER_USAGE, (proxy, context, pathMatcher) -> {
            LimitController controller = new LimitController(proxy, context);
            return controller::getUserUsage;
        });
        get(RouteTemplate.OFFLINE_CREDENTIALS, (proxy, context, pathMatcher) -> {
            OfflineCredentialsController controller = new OfflineCredentialsController(proxy, context);
            return controller::getStatus;
        });
        post(RouteTemplate.OFFLINE_CREDENTIALS_OPERATIONS, (proxy, context, pathMatcher) -> {
            OfflineCredentialsController controller = new OfflineCredentialsController(proxy, context);
            return switch (pathMatcher.group(1)) {
                case "signin" -> controller::signIn;
                case "signout" -> controller::signOut;
                default -> null;
            };
        });
        get(RouteTemplate.USER_CONSENT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            ConsentController controller = new ConsentController(context, proxy);
            return () -> controller.requestConsent(deploymentId);
        });
        get(RouteTemplate.APP_SCHEMAS, (proxy, context, pathMatcher) -> {
            ApplicationTypeSchemaController controller = new ApplicationTypeSchemaController(context);
            String operation = pathMatcher.group(1);
            return switch (operation) {
                case "schemas" -> controller::handleListSchemas;
                case "meta_schema" -> controller::handleGetMetaSchema;
                case "schema" -> controller::handleGetSchema;
                default -> null;
            };
        });
        get(RouteTemplate.CATALOG_SCHEMAS, (proxy, context, pathMatcher) -> {
            CatalogSchemaController controller = new CatalogSchemaController(context);
            String operation = pathMatcher.group(1);
            return switch (operation) {
                case "schemas" -> controller::handleListSchemas;
                case "meta_schema" -> controller::handleGetMetaSchema;
                case "schema" -> controller::handleGetSchema;
                default -> null;
            };
        });
        get(RouteTemplate.TOOL_SET, (proxy, context, pathMatcher) -> {
            ToolSetController controller = new ToolSetController(context);
            String toolsetId = UrlUtil.decodePath(pathMatcher.group(1));
            return () -> controller.getToolSet(toolsetId);
        });
        get(RouteTemplate.TOOL_SETS, (proxy, context, pathMatcher) -> {
            ToolSetController controller = new ToolSetController(context);
            return controller::getToolSets;
        });
        get(RouteTemplate.TOOL_SET_TOOLS, ((proxy, context, pathMatcher) -> {
            String toolSetId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ToolSetToolsController(proxy, context, toolSetId, false);
        }));
        get(RouteTemplate.TOOL_SET_ALLOWED_TOOLS, ((proxy, context, pathMatcher) -> {
            String toolSetId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ToolSetToolsController(proxy, context, toolSetId, true);
        }));
        get(RouteTemplate.TOOL_SET_MCP_PROXY, ((proxy, context, pathMatcher) -> {
            String toolSetId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ToolSetMcpProxyController(proxy, context, toolSetId);
        }));
        get(RouteTemplate.MCP_RESOURCE, ((proxy, context, pathMatcher) -> {
            String applicationId = UrlUtil.decodePath(pathMatcher.group(1));
            return new McpResourceController(proxy, context, applicationId);
        }));
        get(RouteTemplate.APPLICATION_MCP_PROXY, ((proxy, context, pathMatcher) -> {
            String applicationId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ApplicationMcpProxyController(proxy, context, applicationId);
        }));
        get(RouteTemplate.DEPLOYMENT_LISTING, (((proxy, context, pathMatcher) -> {
            DeploymentController controller = new DeploymentController(proxy, context);
            return controller::listDeployments;
        })));
        get(RouteTemplate.LLM_RESPONSES_API_BY_ID, (proxy, context, pathMatcher) -> {
            String id = UrlUtil.decodePath(pathMatcher.group("id"));
            return new ResponseItemController(proxy, context, id, ResponseItemController.Operation.GET);
        });

        // POST routes
        // POST on the per-entity CONFIG_RESOURCE URL is wired so the controller can emit 405 with
        // Allow: GET, PUT, DELETE (RFC 9110 §15.5.6) instead of the router-level 404.
        post(RouteTemplate.CONFIG_RESOURCE, ControllerSelector::configResourceController);
        post(RouteTemplate.POST_DEPLOYMENT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group("id"));
            DeploymentPostController controller = new DeploymentPostController(proxy, context);
            return () -> controller.handle(deploymentId);
        });
        post(RouteTemplate.LLM_RESPONSES_API, (proxy, context, pathMatcher) -> {
            ResponsesController controller = new ResponsesController(proxy, context);
            return controller::handle;
        });
        // count_tokens first: matching is first-registered-wins, so the more specific path must not
        // be swallowed if a greedy /messages/(?<id>...) route is ever added.
        post(RouteTemplate.LLM_MESSAGES_API_COUNT_TOKENS, (proxy, context, pathMatcher) -> {
            MessagesCountTokensController controller = new MessagesCountTokensController(proxy, context);
            return controller::handle;
        });
        post(RouteTemplate.LLM_MESSAGES_API, (proxy, context, pathMatcher) -> {
            MessagesController controller = new MessagesController(proxy, context);
            return controller::handle;
        });
        post(RouteTemplate.LLM_RESPONSES_API_CANCEL, (proxy, context, pathMatcher) -> {
            String id = UrlUtil.decodePath(pathMatcher.group("id"));
            return new ResponseItemController(proxy, context, id, ResponseItemController.Operation.CANCEL);
        });
        post(RouteTemplate.RATE_RESPONSE, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getRateEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new RateResponseController(proxy, context);
            return () -> controller.handle(deploymentId, getter, false);
        });
        post(RouteTemplate.TOKENIZE, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getTokenizeEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        });
        post(RouteTemplate.TRUNCATE_PROMPT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));

            Function<Deployment, String> getter = (model) -> Optional.ofNullable(model)
                    .map(Deployment::getFeatures)
                    .map(Features::getTruncatePromptEndpoint)
                    .orElse(null);

            DeploymentFeatureController controller = new DeploymentFeatureController(proxy, context);
            return () -> controller.handle(deploymentId, getter, true);
        });
        post(RouteTemplate.SHARE_RESOURCE_OPERATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ShareController.Operation op = ShareController.Operation.valueOf(operation.toUpperCase());

            ShareController controller = new ShareController(proxy, context);
            return () -> controller.handle(op);
        });
        post(RouteTemplate.PUBLICATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            PublicationController controller = new PublicationController(proxy, context);

            return switch (operation) {
                case "list" -> controller::listPublications;
                case "get" -> controller::getPublication;
                case "create" -> controller::createPublication;
                case "delete" -> controller::deletePublication;
                case "approve" -> controller::approvePublication;
                case "reject" -> controller::rejectPublication;
                case "update" -> controller::updatePublication;
                default -> null;
            };
        });
        post(RouteTemplate.PUBLICATION_RULES, (proxy, context, pathMatcher) -> {
            PublicationController controller = new PublicationController(proxy, context);
            return controller::listRules;
        });
        post(RouteTemplate.RESOURCE_OPERATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ResourceOperationController controller = new ResourceOperationController(proxy, context);

            return switch (operation) {
                case "move" -> controller::move;
                case "copy" -> controller::copy;
                case "subscribe" -> controller::subscribe;
                default -> null;
            };
        });

        post(RouteTemplate.TOOL_SET_REPAIR, (proxy, context, pathMatcher) -> {
            String bucket = pathMatcher.group("bucket");
            String path = pathMatcher.group("path");
            ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(
                    "toolsets/" + bucket + "/" + path, proxy.getEncryptionService());
            return new ToolSetRepairController(proxy, context, resource)::handle;
        });

        post(RouteTemplate.TOOL_SET_CREDENTIALS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ResourceCredentialsController controller = new ResourceCredentialsController(proxy, context);

            return switch (operation) {
                case "signin" -> controller::signIn;
                case "signout" -> controller::signOut;
                default -> null;
            };
        });
        post(RouteTemplate.EXTERNAL_SERVICE_CREDENTIALS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ExternalServiceCredentialsController controller = new ExternalServiceCredentialsController(proxy, context);

            return switch (operation) {
                case "signin" -> controller::signIn;
                case "signout" -> controller::signOut;
                case "credentials" -> controller::getCredentials;
                case "obo-credentials" -> controller::getOboCredentials;
                default -> null;
            };
        });
        post(RouteTemplate.PUBLISHED_RESOURCES, (proxy, context, pathMatcher) -> {
            PublicationController controller = new PublicationController(proxy, context);
            return controller::listPublishedResources;
        });
        post(RouteTemplate.NOTIFICATIONS, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            NotificationController controller = new NotificationController(proxy, context);

            return switch (operation) {
                case "list" -> controller::listNotifications;
                case "delete" -> controller::deleteNotification;
                default -> null;
            };
        });
        post(RouteTemplate.APPLICATION_OPERATIONS, (proxy, context, pathMatcher) -> {
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
        post(RouteTemplate.CODE_INTERPRETER, (proxy, context, pathMatcher) -> {
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
        post(RouteTemplate.CONFIG_VALIDATE, (proxy, context, pathMatcher) -> new AdminValidateController(proxy, context));
        post(RouteTemplate.CONFIG_APPLY, (proxy, context, pathMatcher) -> {
            AdminApplyController controller = new AdminApplyController(proxy, context);
            return controller::handle;
        });
        post(RouteTemplate.CONFIG_FILE_MIGRATE, (proxy, context, pathMatcher) -> {
            ConfigFileMigrateController controller = new ConfigFileMigrateController(proxy, context);
            return controller::handle;
        });
        get(RouteTemplate.CONFIG_HEALTH, (proxy, context, pathMatcher) -> {
            ConfigAuthorizationService authService = new AdminRoleAuthorizationService(proxy.getAccessService());
            MergedConfigStore mergedConfigStore = (MergedConfigStore) proxy.getConfigStore();
            return new AdminHealthConfigController(context, authService, mergedConfigStore);
        });
        post(RouteTemplate.CONFIG, (proxy, context, pathMatcher) -> new ConfigController(context));
        post(RouteTemplate.ADMIN_CONSENT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            ConsentController controller = new ConsentController(context, proxy);
            return () -> controller.grantAdminConsent(deploymentId);
        });
        post(RouteTemplate.USER_CONSENT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            ConsentController controller = new ConsentController(context, proxy);
            return () -> controller.acceptConsent(deploymentId);
        });
        post(RouteTemplate.TOOL_SET_MCP_PROXY, ((proxy, context, pathMatcher) -> {
            String toolSetId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ToolSetMcpProxyController(proxy, context, toolSetId);
        }));
        post(RouteTemplate.APPLICATION_MCP_PROXY, ((proxy, context, pathMatcher) -> {
            String applicationId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ApplicationMcpProxyController(proxy, context, applicationId);
        }));
        post(RouteTemplate.PER_REQUEST_PERMISSION, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            PerRequestPermissionController controller = new PerRequestPermissionController(context);
            return () -> controller.handle(operation);
        });
        post(RouteTemplate.CLIENT_CHANNEL, (proxy, context, pathMatcher) -> {
            String operation = pathMatcher.group(1);
            ClientChannelController controller = new ClientChannelController(context);

            return switch (operation) {
                case "subscribe" -> controller::subscribe;
                case "report" -> controller::report;
                case "unsubscribe" -> controller::unsubscribe;
                case "interact" -> controller::interact;
                default -> null;
            };
        });
        // DELETE routes
        delete(RouteTemplate.ADMIN_CONSENT, (proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            ConsentController controller = new ConsentController(context, proxy);
            return () -> controller.withdrawAdminConsent(deploymentId);
        });
        delete(RouteTemplate.FILES, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        delete(RouteTemplate.RESOURCE, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        delete(RouteTemplate.CONFIG_RESOURCE, ControllerSelector::configResourceController);
        delete(RouteTemplate.INVITATION, (proxy, context, pathMatcher) -> {
            String invitationId = UrlUtil.decodePath(pathMatcher.group(1));
            InvitationController controller = new InvitationController(proxy, context);
            return () -> controller.deleteInvitation(invitationId);
        });
        delete(RouteTemplate.TOOL_SET_MCP_PROXY, ((proxy, context, pathMatcher) -> {
            String toolSetId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ToolSetMcpProxyController(proxy, context, toolSetId);
        }));
        delete(RouteTemplate.APPLICATION_MCP_PROXY, ((proxy, context, pathMatcher) -> {
            String applicationId = UrlUtil.decodePath(pathMatcher.group(1));
            return new ApplicationMcpProxyController(proxy, context, applicationId);
        }));
        delete(RouteTemplate.LLM_RESPONSES_API_BY_ID, (proxy, context, pathMatcher) -> {
            String id = UrlUtil.decodePath(pathMatcher.group("id"));
            return new ResponseItemController(proxy, context, id, ResponseItemController.Operation.DELETE);
        });
        // PUT routes
        put(RouteTemplate.FILES, (proxy, context, pathMatcher) -> {
            UploadFileController controller = new UploadFileController(proxy, context);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        put(RouteTemplate.RESOURCE, (proxy, context, pathMatcher) -> {
            ResourceController controller = new ResourceController(proxy, context, false);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePath(path));
        });
        put(RouteTemplate.CONFIG_RESOURCE, ControllerSelector::configResourceController);
        put(RouteTemplate.RESOURCE_FOLDER, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, true, true);
            return () -> controller.handle(complexResourceFolderUrl(pathMatcher));
        });
        delete(RouteTemplate.RESOURCE_FOLDER, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, true, true);
            return () -> controller.handle(complexResourceFolderUrl(pathMatcher));
        });
        put(RouteTemplate.COMPLEX_RESOURCE, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, true);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePathV2(path));
        });
        delete(RouteTemplate.COMPLEX_RESOURCE, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, true);
            String path = context.getRequest().path();
            return () -> controller.handle(resourcePathV2(path));
        });
        put(RouteTemplate.COMPLEX_RESOURCE_FILE, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, true,
                    UrlUtil.decodePath(pathMatcher.group("filePath")));
            return () -> controller.handle(complexResourceUrl(pathMatcher));
        });
        delete(RouteTemplate.COMPLEX_RESOURCE_FILE, (proxy, context, pathMatcher) -> {
            ComplexResourceController controller = new ComplexResourceController(proxy, context, true,
                    UrlUtil.decodePath(pathMatcher.group("filePath")));
            return () -> controller.handle(complexResourceUrl(pathMatcher));
        });

        // add deployment routes
        ControllerRoute.Initializer applicationRouteTemplate = ((proxy, context, pathMatcher) -> {
            String deploymentId = UrlUtil.decodePath(pathMatcher.group(1));
            String routePath = pathMatcher.group(2);
            return new ApplicationRouteController(proxy, context, deploymentId, routePath);
        });
        for (HttpMethod method : Proxy.ALLOWED_HTTP_METHODS) {
            ROUTES.add(new ControllerRoute(method, RouteTemplate.DEPLOYMENT_ROUTES.getPattern(), applicationRouteTemplate));
        }
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

    private static void get(RouteTemplate template, ControllerRoute.Initializer controllerTemplate) {
        ROUTES.add(new ControllerRoute(HttpMethod.GET, template.getPattern(), controllerTemplate));
    }

    private static void post(RouteTemplate template, ControllerRoute.Initializer controllerTemplate) {
        ROUTES.add(new ControllerRoute(HttpMethod.POST, template.getPattern(), controllerTemplate));
    }

    private static void put(RouteTemplate template, ControllerRoute.Initializer controllerTemplate) {
        ROUTES.add(new ControllerRoute(HttpMethod.PUT, template.getPattern(), controllerTemplate));
    }

    private static void delete(RouteTemplate template, ControllerRoute.Initializer controllerTemplate) {
        ROUTES.add(new ControllerRoute(HttpMethod.DELETE, template.getPattern(), controllerTemplate));
    }


    private static Controller configResourceController(Proxy proxy, ProxyContext context, Matcher pathMatcher) {
        String entityType = pathMatcher.group(1);
        String bucket = pathMatcher.group("bucket");
        // The {name} segment carries the entity's human-readable name and is percent-encoded by
        // clients (e.g. "new model" → "new%20model"). Decode at the route boundary so the canonical
        // ID and stored entity name match the caller's intent — matches the convention used by the
        // FILES/RESOURCE routes (see ResourceDescriptorFactory.fromAnyUrl) and the {@code path}
        // contract on ResourceDescriptorFactory.fromDecoded ("url decoded relative path").
        String path = UrlUtil.decodePath(pathMatcher.group("path"));
        ConfigAuthorizationService authService = new AdminRoleAuthorizationService(proxy.getAccessService());
        MergedConfigStore mergedConfigStore = (MergedConfigStore) proxy.getConfigStore();
        return new ConfigResourceController(context, authService, mergedConfigStore,
                proxy.getResourceService(), proxy.getTaskExecutor(),
                mergedConfigStore.getSecretFieldProcessor(),
                mergedConfigStore.isSoftValidation(),
                proxy.getApiKeyStore(),
                proxy.getLockService(),
                proxy.getApplicationService(),
                proxy.getToolSetService(),
                entityType, bucket, path);
    }

    private static Controller configResourceMetadataController(Proxy proxy, ProxyContext context, Matcher pathMatcher) {
        String entityType = pathMatcher.group(1);
        String bucket = pathMatcher.group("bucket");
        String path = UrlUtil.decodePath(pathMatcher.group("path"));
        ConfigAuthorizationService authService = new AdminRoleAuthorizationService(proxy.getAccessService());
        return new ConfigResourceMetadataController(context, authService,
                proxy.getResourceService(), proxy.getTaskExecutor(),
                entityType, bucket, path);
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

    private String resourcePathV2(String url) {
        String prefix = "/v2/";

        if (!url.startsWith(prefix)) {
            throw new IllegalArgumentException("Resource url must start with /v2/: " + url);
        }

        return url.substring(prefix.length());
    }

    // Builds the whole-resource url (still percent-encoded, decoded downstream by fromAnyUrl) for a
    // single-file or metadata route match, so access control is evaluated against the whole resource.
    private static String complexResourceUrl(Matcher matcher) {
        return "skills/" + matcher.group("bucket") + "/" + matcher.group("path");
    }

    // Builds the grouping-folder url (trailing slash so fromAnyUrl marks it a folder) for folder ops and the
    // children metadata listing. An empty path lists the bucket root.
    private static String complexResourceFolderUrl(Matcher matcher) {
        String path = matcher.group("path");
        if (path != null && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        String suffix = (path == null || path.isEmpty()) ? "" : path + "/";
        return "skills/" + matcher.group("bucket") + "/" + suffix;
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