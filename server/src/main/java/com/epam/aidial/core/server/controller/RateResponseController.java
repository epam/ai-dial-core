package com.epam.aidial.core.server.controller;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.enhancement.HandleRateResponseFn;

public class RateResponseController extends DeploymentFeatureController {
    public RateResponseController(Proxy proxy, ProxyContext context) {
        super(proxy, context);
        this.enhancementFunctions.add(new HandleRateResponseFn(proxy, context));
    }
}
