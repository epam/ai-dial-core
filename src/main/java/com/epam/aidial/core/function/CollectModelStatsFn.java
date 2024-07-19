package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CollectModelStatsFn extends BaseFunction<ObjectNode> {
    public CollectModelStatsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    /**
     * The function doesn't validate anything just collects model stats from the request.
     *
     * @param requestBody the JSON request body
     * @return the error is always <code>null</code>
     */
    @Override
    public Throwable apply(ObjectNode requestBody) {
        proxy.getDeploymentCostStatsTracker().handleRequestBody(requestBody, context);
        return null;
    }
}
