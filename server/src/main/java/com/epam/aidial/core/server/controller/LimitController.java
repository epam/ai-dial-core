package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.config.RoleBasedEntity;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.data.UserLimitStats;
import com.epam.aidial.core.server.service.ApplicationService;
import com.epam.aidial.core.server.service.DeploymentService;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import com.epam.aidial.core.storage.resource.ResourceTypes;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Slf4j
public class LimitController {

    private static final String MODEL_TYPE = "model";
    private static final String APPLICATION_TYPE = "application";

    private final Proxy proxy;

    private final ProxyContext context;

    private final DeploymentService deploymentService;
    private final ApplicationService applicationService;

    public LimitController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
        this.deploymentService = proxy.getDeploymentService();
        this.applicationService = proxy.getApplicationService();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/deployments/{deployment_name}/limits",
            operationId = "getDeploymentLimits",
            tags = {"Limits"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = LimitStats.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            },
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME)
            }
    )
    public Future<?> getDeploymentLimits(String deploymentId) {
        proxy.getTaskExecutor().submit(() -> proxy.getDeploymentService().findDeployment(context, deploymentId))
                .compose(dep -> proxy.getRateLimiter().getLimitStats(dep, context))
                .onSuccess(limitStats -> {
                    if (limitStats == null) {
                        context.respond(HttpStatus.NOT_FOUND);
                    } else {
                        context.respond(HttpStatus.OK, limitStats);
                    }
                }).onFailure(error -> handleRequestError(deploymentId, error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/deployments/{deployment_name}/usage",
            operationId = "getDeploymentUsage",
            tags = {"Limits"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = LimitStats.class)),
                    @ApiResponse(code = 403),
                    @ApiResponse(code = 404),
                    @ApiResponse(code = 500)
            },
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME)
            }
    )
    public Future<?> getDeploymentUsage(String deploymentId) {
        proxy.getTaskExecutor().submit(() -> proxy.getDeploymentService().findDeployment(context, deploymentId))
                .compose(dep -> proxy.getRateLimiter().getDeploymentUsage(dep, context))
                .onSuccess(limitStats -> {
                    if (limitStats == null) {
                        context.respond(HttpStatus.NOT_FOUND);
                    } else {
                        context.respond(HttpStatus.OK, limitStats);
                    }
                }).onFailure(error -> handleRequestError(deploymentId, error));

        return Future.succeededFuture();
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/user/limits",
            operationId = "getUserLimits",
            tags = {"Limits"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = UserLimitStats.class)),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500)
            },
            parameters = {
                    @ApiParameter(name = "deploymentTypes", in = ParameterIn.QUERY,
                            schema = String[].class,
                            description = OpenApiDescriptions.DEPLOYMENT_TYPES,
                            allowableValues = {MODEL_TYPE, APPLICATION_TYPE})
            }
    )
    public Future<?> getUserLimits() {
        return respondWithUserStats(false, getDeploymentTypes());
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/user/usage",
            operationId = "getUserUsage",
            tags = {"Limits"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = UserLimitStats.class)),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500)
            },
            parameters = {
                    @ApiParameter(name = "deploymentTypes", in = ParameterIn.QUERY,
                            schema = String[].class,
                            description = OpenApiDescriptions.DEPLOYMENT_TYPES,
                            allowableValues = {MODEL_TYPE, APPLICATION_TYPE})
            }
    )
    public Future<?> getUserUsage() {
        return respondWithUserStats(true, getDeploymentTypes());
    }

    private Set<String> getDeploymentTypes() {
        return Set.of(context.getRequest().getParam("deploymentTypes", MODEL_TYPE).split(","));
    }

    private Future<?> respondWithUserStats(boolean dropEmpty, Set<String> deploymentTypes) {
        proxy.getTaskExecutor().submit(() -> listAccessibleDeployments(deploymentTypes))
                .compose(deployments -> proxy.getRateLimiter().getUserStats(context, deployments, dropEmpty))
                .onSuccess(stats -> context.respond(HttpStatus.OK, stats))
                .onFailure(this::handleUserLimitsError);

        return Future.succeededFuture();
    }

    /**
     * Enumerates the deployments to report on, selected purely from config/resources before any storage
     * listing runs - so requesting more {@code deploymentTypes} costs no extra listing. Model reporting is
     * unchanged from before this parameter existed: DIAL writes direct-cost rate-limit counters for every
     * Model. Application reporting covers a deployment's <i>aggregated</i> cost - what rolled up from
     * descendants it called in a chain - not a self-reported direct cost, since DIAL never writes a
     * direct-cost counter for an Application. See {@link UserLimitStats}.
     *
     * <p>A custom application is listed by name only ({@link DeploymentService#listDeploymentNames}),
     * never fully extracted: {@link com.epam.aidial.core.server.limiter.RateLimiter} only ever calls
     * {@link RoleBasedEntity#getName()}/{@link RoleBasedEntity#getUserRoles()} on these entries, so
     * reading every accessible application's full body and resolving its schema/mcp/viewerUrl on top
     * would be pure waste here.
     */
    private List<RoleBasedEntity> listAccessibleDeployments(Set<String> deploymentTypes) {
        List<RoleBasedEntity> deployments = new ArrayList<>();
        if (deploymentTypes.contains(MODEL_TYPE)) {
            for (Model model : context.getConfig().getModels().values()) {
                if (model.hasAccess(context.getUserRoles())) {
                    deployments.add(model);
                }
            }
        }
        if (deploymentTypes.contains(APPLICATION_TYPE)) {
            for (Application application : context.getConfig().getApplications().values()) {
                if (application.hasAccess(context.getUserRoles())) {
                    deployments.add(application);
                }
            }
            if (applicationService.isIncludeCustomApps()) {
                deployments.addAll(deploymentService.listDeploymentNames(context, ResourceTypes.APPLICATION, Application::new));
            }
        }
        return deployments;
    }

    private void handleUserLimitsError(Throwable error) {
        // every authenticated caller has an initiator bucket - a project key without a project is rejected at
        // load time and a token carries a subject - so anything reaching here is a server-side fault
        context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get user limit stats");
        log.error("LimitController. Failed to get user limit stats", error);
    }

    private void handleRequestError(String deploymentId, Throwable error) {
        if (error instanceof PermissionDeniedException) {
            context.respond(HttpStatus.FORBIDDEN, error.getMessage());
            log.warn("LimitController. Forbidden deployment {}", deploymentId);
        } else if (error instanceof ResourceNotFoundException) {
            context.respond(HttpStatus.NOT_FOUND, error.getMessage());
            log.warn("LimitController. Deployment not found {}", deploymentId, error);
        } else {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Failed to get limit stats for deployment=%s".formatted(deploymentId));
            log.error("LimitController. Failed to get limit stats", error);
        }
    }

}