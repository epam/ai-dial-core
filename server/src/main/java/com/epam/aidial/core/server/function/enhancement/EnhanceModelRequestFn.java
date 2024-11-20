package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnhanceModelRequestFn extends BaseRequestFunction<ObjectNode> {
    public EnhanceModelRequestFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(ObjectNode tree) {
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Model) {
            return enhanceModelRequest(context, tree);
        }
        return false;
    }

    private static boolean enhanceModelRequest(ProxyContext context, ObjectNode tree) {
        Model model = (Model) context.getDeployment();
        String overrideName = model.getOverrideName();

        if (overrideName == null) {
            return false;
        }

        tree.remove("model");
        tree.put("model", overrideName);

        return true;
    }
}
