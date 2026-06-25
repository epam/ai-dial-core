package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Config;
import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.metaschemas.MetaSchemaHolder;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.controller.extraction.ApplicationDeploymentExtractor;
import com.epam.aidial.core.server.data.ApplicationData;
import com.epam.aidial.core.server.data.FeaturesData;
import com.epam.aidial.core.server.data.ListData;
import com.epam.aidial.core.server.data.ResourceLink;
import com.epam.aidial.core.server.security.AccessService;
import com.epam.aidial.core.server.security.EncryptionService;
import com.epam.aidial.core.server.service.ApplicationSchemaService;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.server.util.ProxyUtil;
import com.epam.aidial.core.server.util.ResourceDescriptorFactory;
import com.epam.aidial.core.server.vertx.AsyncTaskExecutor;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;


@Slf4j
public class ApplicationController {

    private final ProxyContext context;
    private final EncryptionService encryptionService;
    private final AccessService accessService;
    private final ApplicationService applicationService;

    private final DeploymentService deploymentService;
    private final ApplicationSchemaService applicationSchemaService;

    private final DeploymentService.DeploymentExtractor deploymentExtractor;

    private final AsyncTaskExecutor taskExecutor;

    public ApplicationController(ProxyContext context) {
        this.context = context;
        this.encryptionService = context.getProxy().getEncryptionService();
        this.accessService = context.getProxy().getAccessService();
        this.applicationService = context.getProxy().getApplicationService();
        this.deploymentService = context.getProxy().getDeploymentService();
        this.applicationSchemaService = context.getProxy().getApplicationSchemaService();
        this.deploymentExtractor = new ApplicationDeploymentExtractor(accessService, applicationService, applicationSchemaService);
        this.taskExecutor = context.getProxy().getTaskExecutor();
    }

    @ApiOperation(
            method = "GET",
            path = "/openai/applications/{application_name}",
            operationId = "getApplication",
            tags = {"Deployment listing"},
            parameters = {
                    @ApiParameter(name = "application_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.APPLICATION_NAME)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = ApplicationData.class)
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    public Future<?> getApplication(String applicationId) {
        taskExecutor.submit(() -> deploymentService.findDeployment(context, applicationId))
                .map(deployment -> {
                    if (deployment instanceof Application application) {
                        boolean applicationRequestInfoAboutItSelf = applicationId.equals(context.getDecodedSourceDeployment());
                        if (application.hasApplicationTypeSchemaId()) {
                            application.setMcp(applicationSchemaService.getMcp(application));
                            application.setViewerUrl(applicationSchemaService.getStringProperty(application, MetaSchemaHolder.APPLICATION_TYPE_VIEWER_URL));
                        }
                        return applicationSchemaService.modifySchemaRichApplication(application, !applicationRequestInfoAboutItSelf);
                    }
                    throw new ResourceNotFoundException("Application is not found: " + applicationId);
                })
                .map(this::mapApplication)
                .onSuccess(data -> context.respond(HttpStatus.OK, data))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/openai/applications",
            operationId = "getApplications",
            tags = {"Deployment listing"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = ApplicationData.class, wrapper = ListData.class)
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    public Future<?> getApplications() {
        log.debug("Start applications listing");
        Config config = context.getConfig();
        Proxy proxy = context.getProxy();

        return proxy.getTaskExecutor().submit(() -> {
            List<Application> list = new ArrayList<>();
            for (Application application : config.getApplications().values()) {
                if (application.hasAccess(context.getUserRoles())) {
                    boolean applicationRequestInfoAboutItSelf = Objects.equals(context.getDecodedSourceDeployment(), application.getName());
                    application = applicationSchemaService.modifySchemaRichApplication(application, !applicationRequestInfoAboutItSelf);
                    list.add(application);
                }
            }
            if (applicationService.isIncludeCustomApps()) {
                list.addAll(deploymentService.listDeployments(context, ResourceTypes.APPLICATION, deploymentExtractor));
            }
            return list.stream().map(this::mapApplication).toList();
        }).onSuccess(apps -> {
            log.debug("Finish applications listing");
            context.respond(HttpStatus.OK, new ListData<>(apps));
        }).onFailure(this::respondError);
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/application/deploy",
            operationId = "deployApplication",
            requestBody = ResourceLink.class,
            tags = {"Applications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Application.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> deployApplication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodeUrl(url);
                    checkAccess(resource);
                    return taskExecutor.submit(() -> applicationService.deployApplication(context, resource));
                })
                .onSuccess(application -> context.respond(HttpStatus.OK, application))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/application/undeploy",
            operationId = "undeployApplication",
            requestBody = ResourceLink.class,
            tags = {"Applications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Application.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> undeployApplication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodeUrl(url);
                    checkAccess(resource);
                    return taskExecutor.submit(() -> applicationService.undeployApplication(resource));
                })
                .onSuccess(application -> context.respond(HttpStatus.OK, application))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/application/redeploy",
            operationId = "redeployApplication",
            requestBody = ResourceLink.class,
            tags = {"Applications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Application.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> redeployApplication() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodeUrl(url);
                    checkAccess(resource);
                    return taskExecutor.submit(() -> applicationService.redeployApplication(context, resource));
                })
                .onSuccess(application -> context.respond(HttpStatus.OK, application))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "POST",
            path = "/v1/ops/application/logs",
            operationId = "getApplicationLogs",
            requestBody = ResourceLink.class,
            tags = {"Applications"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = Application.Logs.class)
            },
            responseProfile = ResponseProfile.AUTHORIZED_OPERATION
    )
    public Future<?> getApplicationLogs() {
        context.getRequest()
                .body()
                .compose(body -> {
                    String url = ProxyUtil.convertToObject(body, ResourceLink.class).url();
                    ResourceDescriptor resource = decodeUrl(url);
                    checkAccess(resource);
                    return taskExecutor.submit(() -> applicationService.getApplicationLogs(resource));
                })
                .onSuccess(logs -> context.respond(HttpStatus.OK, logs))
                .onFailure(this::respondError);

        return Future.succeededFuture();
    }

    private ResourceDescriptor decodeUrl(String url) {
        ResourceDescriptor resource;
        try {
            resource = ResourceDescriptorFactory.fromAnyUrl(url, encryptionService);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid application: " + url, e);
        }

        if (resource.getType() != ResourceTypes.APPLICATION) {
            throw new IllegalArgumentException("Invalid application: " + url);
        }

        return resource;
    }

    private void checkAccess(ResourceDescriptor resource) {
        boolean hasAccess = accessService.hasWriteAccess(resource, context);

        if (hasAccess) {
            Application application = applicationService.getApplication(resource).getValue();
            Application.Function function = application.getFunction();

            if (function != null) {
                ResourceDescriptor sources = ResourceDescriptorFactory.fromAnyUrl(function.getSourceFolder(), encryptionService);
                hasAccess = accessService.hasWriteAccess(sources, context);
            }
        }

        if (!hasAccess) {
            throw new HttpException(HttpStatus.FORBIDDEN, "Forbidden operation for application: " + resource.getUrl());
        }
    }

    private void respondError(Throwable error) {
        switch (error) {
            case IllegalArgumentException ignored ->
                    context.respond(HttpStatus.BAD_REQUEST, error.getMessage());
            case PermissionDeniedException ignored ->
                    context.respond(HttpStatus.FORBIDDEN, error.getMessage());
            case ResourceNotFoundException ignored ->
                    context.respond(HttpStatus.NOT_FOUND, error.getMessage());
            case null, default -> {
                context.respond(error, "Internal error");
                log.error("Failed to handle application request", error);
            }
        }
    }

    private boolean hasWriteAccess(Application application) {
        String appName = application.getName();
        if (appName != null && appName.equals(context.getDecodedSourceDeployment())) {
            return true;
        }
        if (appName != null) {
            try {
                ResourceDescriptor resource = ResourceDescriptorFactory.fromAnyUrl(appName, encryptionService);
                if (resource.getType() == ResourceTypes.APPLICATION) {
                    return accessService.hasWriteAccess(resource, context);
                }
            } catch (Exception e) {
                // appName is not DIAL resource url, fall through to admin check
            }
        }
        return accessService.hasAdminAccess(context);
    }

    private static void clearUpstreams(Map<String, Route> routes) {
        routes.forEach((name, route) -> route.setUpstreams(null));
    }

    private ApplicationData mapApplication(Application application) {
        ApplicationData data = new ApplicationData();
        data.setInvalid(application.getInvalid());
        data.setId(application.getName());
        data.setApplication(application.getName());
        if (application.getDisplayName() != null) {
            data.setDisplayName(application.getDisplayName());
        } else {
            data.setDisplayName(application.getName());
        }
        data.setDisplayVersion(application.getDisplayVersion());
        data.setIconUrl(application.getIconUrl());
        data.setDescription(application.getDescription());
        FeaturesData featuresData = FeaturesData.createFeatures(application.getFeatures());
        featuresData.setMcp(application.getMcp() != null);
        featuresData.setChatCompletion(application.getEndpoint() != null);
        featuresData.setResponsesApi(application.getResponsesEndpoint() != null);
        data.setFeatures(featuresData);
        data.setInputAttachmentTypes(application.getInputAttachmentTypes());
        data.setMaxInputAttachments(application.getMaxInputAttachments());
        data.setDefaults(application.getDefaults());
        data.setResponsesDefaults(application.getResponsesDefaults());
        data.setDescriptionKeywords(application.getDescriptionKeywords());

        data.setApplicationTypeSchemaId(application.getApplicationTypeSchemaId());
        data.setApplicationProperties(application.getApplicationProperties());
        String reference = application.getReference();
        data.setReference(reference == null ? application.getName() : reference);
        data.setFunction(application.getFunction());
        data.setMaxRetryAttempts(application.getMaxRetryAttempts());

        if (application.getAuthor() != null) {
            data.setOwner(application.getAuthor());
        }
        if (application.getCreatedAt() != null) {
            data.setCreatedAt(application.getCreatedAt());
        }
        if (application.getUpdatedAt() != null) {
            data.setUpdatedAt(application.getUpdatedAt());
        }

        Map<String, Route> routes = null;
        if (application.hasApplicationTypeSchemaId()) {
            routes = applicationSchemaService.getRoutes(application);
        }
        if (routes == null) {
            routes = application.getRoutes();
        }
        if (!hasWriteAccess(application) && routes != null) {
            clearUpstreams(routes);
        }
        data.setRoutes(routes);
        data.setViewerUrl(application.getViewerUrl());
        data.setEditorUrl(application.getEditorUrl());

        return data;
    }

}