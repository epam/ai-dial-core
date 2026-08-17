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
        return respondWithUserStats(false);
    }

    @ApiOperation(
            method = "GET",
            path = "/v1/user/usage",
            operationId = "getUserUsage",
            tags = {"Limits"},
            responses = {
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = UserLimitStats.class)),
                    @ApiResponse(code = 401),
                    @ApiResponse(code = 500),
                    @ApiResponse(code = 503)
            }
    )
    public Future<?> getUserUsage() {
        return respondWithUserStats(true);
    }

    private Future<?> respondWithUserStats(boolean dropEmpty) {
        // no executor hop: listAccessibleModels only streams over the in-memory config, and
        // getUserStats submits the blocking part itself
        List<Model> models = listAccessibleModels();
        proxy.getRateLimiter().getUserStats(context, models, dropEmpty)
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
     * Only models are reported: DIAL never writes rate-limit counters for applications, toolsets or routes,
     * so an entry for one would report zeros against a limit that cannot fire. See {@link UserLimitStats}.
     */
    private List<Model> listAccessibleModels() {
        return context.getConfig().getModels().values().stream()
                .filter(model -> model.hasAccess(context.getUserRoles()))
                .toList();
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