package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Model;
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
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class LimitController {

    private final Proxy proxy;

    private final ProxyContext context;

    public LimitController(Proxy proxy, ProxyContext context) {
        this.proxy = proxy;
        this.context = context;
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
    public Future<?> getLimits(String deploymentId) {
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
            path = "/v1/user/limits",
            operationId = "getUserLimits",
            tags = {"Limits"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = UserLimitStats.class)),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 503)
            }
    )
    public Future<?> getUserLimits() {
        // no executor hop: listAccessibleModels only streams over the in-memory config, and
        // getUserLimitStats submits the blocking part itself
        proxy.getRateLimiter().getUserLimitStats(context, listAccessibleModels())
                .onSuccess(stats -> {
                    if (stats == null) {
                        context.respond(HttpStatus.SERVICE_UNAVAILABLE, "Limit storage is not available");
                    } else {
                        context.respond(HttpStatus.OK, stats);
                    }
                }).onFailure(this::handleUserLimitsError);

        return Future.succeededFuture();
    }

    /**
     * Only models are reported: DIAL does not persist rate-limit history for applications, toolsets or
     * routes, so their entries would be permanently zero. See {@link UserLimitStats}.
     */
    private List<Model> listAccessibleModels() {
        return context.getConfig().getModels().values().stream()
                .filter(model -> model.hasAccess(context.getUserRoles()))
                .toList();
    }

    private void handleUserLimitsError(Throwable error) {
        if (error instanceof IllegalArgumentException) {
            // neither a JWT subject nor an API-key project - there is no caller to report limits for
            context.respond(HttpStatus.UNAUTHORIZED, error.getMessage());
            log.warn("LimitController. Can't resolve the request initiator", error);
        } else {
            context.respond(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to get user limit stats");
            log.error("LimitController. Failed to get user limit stats", error);
        }
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