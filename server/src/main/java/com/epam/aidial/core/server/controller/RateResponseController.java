package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.enhancement.HandleRateResponseFn;
import com.epam.aidial.core.server.openapi.ApiOperation;
import io.vertx.core.Future;

import java.util.function.Function;

public class RateResponseController extends DeploymentFeatureController {
    public RateResponseController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions.add(new HandleRateResponseFn(proxy, context));
    }

    @Override
    @ApiOperation(method = "POST", path = "/v1/{deployment_name}/rate", operationId = "rateDeployment")
    public Future<?> handle(String deploymentId, Function<Deployment, String> endpointGetter, boolean requireEndpoint) {
        return super.handle(deploymentId, endpointGetter, requireEndpoint);
    }
}
