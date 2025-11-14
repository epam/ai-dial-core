package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class HandleRateResponseFn extends BaseRequestFunction<ObjectNode> {

    public HandleRateResponseFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (!isSupportCommentInRateResponse(deployment) && tree.has("comment")) {
            tree.remove("comment");
            return true;
        }
        return false;
    }

    private boolean isSupportCommentInRateResponse(Deployment deployment) {
        return deployment.getFeatures() != null
                && Boolean.TRUE.equals(deployment.getFeatures().getSupportCommentInRateResponse());
    }

}
