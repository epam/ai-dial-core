package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.InterfaceType;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.ModelType;
import com.epam.aidial.core.config.ToolSet;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.extraction.ApplicationDeploymentExtractor;
import com.epam.aidial.core.server.data.ApplicationData;
import com.epam.aidial.core.server.data.DeploymentData;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ToolSetData;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.ToolSetService;
import com.epam.aidial.core.storage.data.ResourceItemMetadata;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.CompositeFuture;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import static com.epam.aidial.core.server.controller.ModelController.createModel;

@Slf4j
public class DeploymentController {

    private static final String CHAT_IFACE = "chat";
    private static final String EMBEDDING_IFACE = "embedding";
    private static final String MCP_IFACE = "mcp";
    private static final String CUSTOM_UI_IFACE = "custom_ui";
    private static final String ALL_IFACE = "all";
    private final Proxy proxy;
    private final ProxyContext context;
    private final DeploymentService.DeploymentExtractor appExtractor;
    private final DeploymentService deploymentService;
    private final ApplicationService applicationService;
    private final ToolSetService toolSetService;
    private final ApplicationSchemaService applicationSchemaService;

    public DeploymentController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.appExtractor = new ApplicationDeploymentExtractor(proxy.getAccessService(),
                proxy.getApplicationService(), proxy.getApplicationSchemaService());
        this.deploymentService = proxy.getDeploymentService();
        this.applicationService = proxy.getApplicationService();
        this.toolSetService = proxy.getToolSetService();
        this.applicationSchemaService = proxy.getApplicationSchemaService();

    }

    @ApiOperation(
            method = "GET",
            path = "/openai/deployments/{deployment_name}",
            operationId = "getDeployment",
            tags = {"Deployment listing"},
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = DeploymentData.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404)
            }
    )
    public Future<?> getDeployment(String deploymentId) {
        Config config = context.getConfig();
        Model model = config.getModels().get(deploymentId);

        if (model == null) {
            return context.respond(HttpStatus.NOT_FOUND);
        }

        if (!model.hasAccess(context.getUserRoles())) {
            return context.respond(HttpStatus.FORBIDDEN);
        }

        DeploymentData data = createModel(model);
        context.respond(HttpStatus.OK, data);
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/openai/deployments",
            operationId = "getDeployments",
            tags = {"Deployment listing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = ListData.class, typeArguments = {DeploymentData.class})),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> getDeployments() {
        getModels(List.of()).onSuccess(deployments -> {
            ListData<DeploymentData> list = new ListData<>();
            list.setData(deployments);
            context.respond(HttpStatus.OK, list);
        }).onFailure(error -> {
            log.error("Error occurred on listing models", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        });
        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/deployments",
            operationId = "listDeployments",
            tags = {"Deployment listing"},
            parameters = {
                    @ApiParameter(name = "interface_type", in = ParameterIn.QUERY,
                            schema = String[].class,
                            description = OpenApiDescriptions.INTERFACE_TYPE,
                            allowableValues = {"chat", "embedding", "mcp", "custom_ui", "all"})
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = List.class, typeArguments = {DeploymentData.class})),
                    @ApiResponse(code = 400),
                    @ApiResponse(code = 500)
            }
    )
    public Future<?> listDeployments() {
        String[] interfaces = getDeploymentInterfaces();

        ExecutionPlan plan;
        try {
            plan = build(interfaces);
        } catch (Exception e) {
            context.respond(HttpStatus.BAD_REQUEST, e.getMessage());
            return Future.succeededFuture();
        }

        CompositeFuture future = execute(plan);

        future.onSuccess(result -> {
            List<DeploymentData> deployments = new ArrayList<>();
            for (int i = 0; i < result.size(); i++) {
                deployments.addAll(result.resultAt(i));
            }
            context.respond(HttpStatus.OK, deployments);
        }).onFailure(error -> {
            log.error("Error occurred on listing deployments", error);
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, error.getMessage());
        });

        return Future.succeededFuture();
    }


    private String[] getDeploymentInterfaces() {
        String interfacesParam = context.getRequest().getParam("interface_type", "all");
        return interfacesParam.split(",");
    }

    private CompositeFuture execute(ExecutionPlan plan) {
        List<Future<List<DeploymentData>>> futures = new ArrayList<>();
        if (plan.useModels) {
            futures.add(getModels(plan.modelFilters));
        }
        if (plan.useApplications) {
            futures.add(getApplications(plan.appFilters));
        }
        if (plan.useToolsets) {
            futures.add(getToolSets());
        }
        return Future.all(futures);
    }

    private ExecutionPlan build(String[] interfaces) {
        ExecutionPlan plan = new ExecutionPlan();
        for (String iface : interfaces) {
            switch (iface) {
                case CHAT_IFACE: {
                    plan.useModels = true;
                    plan.useApplications = true;
                    plan.appFilters.add(app -> app.supportsInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS));
                    plan.modelFilters.add(model -> model.getType() == ModelType.CHAT);
                    break;
                }
                case EMBEDDING_IFACE: {
                    plan.useModels = true;
                    plan.modelFilters.add(model -> model.getType() == ModelType.EMBEDDING);
                    break;
                }
                case MCP_IFACE: {
                    plan.useApplications = true;
                    plan.useToolsets = true;
                    plan.appFilters.add(app -> app.getMcp() != null);
                    break;
                }
                case CUSTOM_UI_IFACE: {
                    plan.useApplications = true;
                    plan.appFilters.add(app -> app.getViewerUrl() != null);
                    break;
                }
                case ALL_IFACE: {
                    plan.useApplications = true;
                    plan.useToolsets = true;
                    plan.useModels = true;
                    plan.appFilters.clear();
                    plan.modelFilters.clear();
                    return plan;
                }
                default: {
                    throw new IllegalArgumentException("Unsupported deployment interface is provided: " + iface);
                }
            }
        }
        return plan;
    }

    private Future<List<DeploymentData>> getApplications(List<Predicate<Application>> filters) {
        return proxy.getTaskExecutor().submit(() -> {
            Config config = context.getConfig();
            List<DeploymentData> deployments = new ArrayList<>();
            for (Application application : config.getApplications().values()) {
                if (application.hasAccess(context.getUserRoles())) {
                    Application resolved = resolveLocalApplication(application);
                    if (match(filters, resolved)) {
                        deployments.add(to(resolved));
                    }
                }
            }
            List<Application> resourceApps = List.of();
            if (applicationService.isIncludeCustomApps()) {
                resourceApps = deploymentService.listDeployments(context, ResourceTypes.APPLICATION, appExtractor);
            }
            for (Application app : resourceApps) {
                if (match(filters, app)) {
                    deployments.add(to(app));
                }
            }
            return deployments;
        });
    }

    private Application resolveLocalApplication(Application application) {
        boolean applicationRequestInfoAboutItSelf = Objects.equals(context.getDecodedSourceDeployment(), application.getName());
        application = applicationSchemaService.modifySchemaRichApplication(application, !applicationRequestInfoAboutItSelf);
        if (application.hasApplicationTypeSchemaId()) {
            application.setMcp(applicationSchemaService.getMcp(application));
            application.setViewerUrl(applicationSchemaService.getStringProperty(application,
                    MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL));
        }
        return application;
    }

    private Future<List<DeploymentData>> getToolSets() {
        return proxy.getTaskExecutor().submit(() -> {
            List<DeploymentData> deployments = new ArrayList<>();
            List<ToolSet> resourceToolsets = deploymentService.listDeployments(context, ResourceTypes.TOOL_SET, new DeploymentService.DeploymentExtractor() {
                @SuppressWarnings("unchecked")
                @Override
                public ToolSet extract(String content, ResourceItemMetadata metadata, ProxyContext context) {
                    return toolSetService.extractFrom(content, metadata);
                }
            });
            for (ToolSet toolSet : resourceToolsets) {
                deployments.add(to(toolSet));
            }
            Config config = context.getConfig();
            for (ToolSet toolSet : config.getToolsets().values()) {
                if (toolSet.hasAccess(context.getUserRoles())) {
                    deployments.add(to(toolSet));
                }
            }
            return deployments;
        });
    }

    private Future<List<DeploymentData>> getModels(List<Predicate<Model>> filters) {
        List<DeploymentData> deployments = new ArrayList<>();
        Config config = context.getConfig();
        for (Model model : config.getModels().values()) {
            if (model.hasAccess(context.getUserRoles()) && match(filters, model)) {
                DeploymentData deployment = createModel(model);
                List<String> interfaces = new ArrayList<>();
                if (model.getType() == ModelType.CHAT) {
                    interfaces.add(CHAT_IFACE);
                } else {
                    interfaces.add(EMBEDDING_IFACE);
                }
                interfaces.addAll(supportedInterfaces(model));
                deployment.setInterfaces(interfaces);
                deployments.add(deployment);
            }
        }
        return Future.succeededFuture(deployments);
    }

    private static ApplicationData to(Application app) {
        ApplicationData applicationData = ApplicationData.mapApplication(app);
        List<String> interfaces = new ArrayList<>();
        if (app.getMcp() != null) {
            interfaces.add(MCP_IFACE);
        }
        if (app.supportsInterface(InterfaceType.OPENAI_CHAT_COMPLETIONS)) {
            interfaces.add(CHAT_IFACE);
        }
        if (app.getViewerUrl() != null) {
            interfaces.add(CUSTOM_UI_IFACE);
        }
        interfaces.addAll(supportedInterfaces(app));
        applicationData.setInterfaces(interfaces);
        return applicationData;
    }

    private static ToolSetData to(ToolSet toolSet) {
        ToolSetData toolSetData = ToolSetData.toData(toolSet);
        // don't return login status because of performance reasons
        toolSetData.setAuthSettings(null);
        toolSetData.setInterfaces(List.of(MCP_IFACE));
        return toolSetData;
    }

    /**
     * Typed interface types ({@link InterfaceType}) the deployment declares, via either the new
     * {@code interfaces} map or a legacy endpoint. A deployment that still serves embeddings through the
     * untyped legacy {@code endpoint} is listed under {@code openaiChatCompletions} only, since that is
     * what it declares.
     */
    private static List<String> supportedInterfaces(Deployment deployment) {
        List<String> result = new ArrayList<>();
        for (InterfaceType type : InterfaceType.values()) {
            if (deployment.supportsInterface(type)) {
                result.add(type.getValue());
            }
        }
        return result;
    }

    private static class ExecutionPlan {
        List<Predicate<Model>> modelFilters = new ArrayList<>();
        List<Predicate<Application>> appFilters = new ArrayList<>();
        boolean useToolsets;
        boolean useApplications;
        boolean useModels;
    }

    private static <T extends Deployment> boolean match(List<Predicate<T>> filters, T app) {
        boolean matched = filters.isEmpty();
        for (Predicate<T> filter : filters) {
            if (filter.test(app)) {
                matched = true;
                break;
            }
        }
        return matched;
    }

}