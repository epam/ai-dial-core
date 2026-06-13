package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.openapi.annotations.ApiOperation;
import com.epam.aidial.core.openapi.annotations.ApiParameter;
import com.epam.aidial.core.openapi.annotations.ApiResponse;
import com.epam.aidial.core.openapi.annotations.OpenApiDescriptions;
import com.epam.aidial.core.openapi.annotations.ParameterIn;
import com.epam.aidial.core.openapi.annotations.ResponseProfile;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.enhancement.HandleRateResponseFn;
import io.vertx.core.Future;

import java.util.function.Function;

public class RateResponseController extends DeploymentFeatureController {
    public RateResponseController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions.add(new HandleRateResponseFn(proxy, context));
    }

    @Override
    @ApiOperation(
            method = "POST",
            path = "/v1/{deployment_name}/rate",
            operationId = "rateDeployment",
            requestBodySchemaRef = "RateRequest",
            parameters = {
                    @ApiParameter(name = "deployment_name", in = ParameterIn.PATH, required = true,
                            description = OpenApiDescriptions.DEPLOYMENT_NAME)
            },
            responses = {
                    @ApiResponse(code = 200, description = "Success")
            },
            responseProfile = ResponseProfile.AUTHENTICATED_READ_EXTENDED
    )
    public Future<?> handle(String deploymentId, Function<Deployment, String> endpointGetter, boolean requireEndpoint) {
        return super.handle(deploymentId, endpointGetter, requireEndpoint);
    }
}