package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CollectDeploymentsFn extends BaseRequestFunction<ObjectNode> {

    public CollectDeploymentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        if (context.getDeployment() instanceof Application application) {
            shareApplicationDeployments(application);
        }
        return false;
    }
}
