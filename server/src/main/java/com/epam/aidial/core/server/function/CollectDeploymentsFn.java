package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Application;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;

public class CollectDeploymentsFn extends BaseRequestFunction<RequestObject> {

    public CollectDeploymentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        if (context.getDeployment() instanceof Application application) {
            shareApplicationDeployments(application);
        }
        return false;
    }
}
