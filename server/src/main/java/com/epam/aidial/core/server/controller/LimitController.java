package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.ApiSchema;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.data.LimitStats;
import com.epam.aidial.core.server.service.PermissionDeniedException;
import com.epam.aidial.core.storage.exception.ResourceNotFoundException;
import com.epam.aidial.core.storage.http.HttpStatus;
import io.vertx.core.Future;
import lombok.extern.slf4j.Slf4j;

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
                    @ApiResponse(code = 200, description = "Success", body = @ApiSchema(implementation = LimitStats.class))
            },
            responseProfile = ResponseProfile.LIMIT_WITH_NOT_FOUND,
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