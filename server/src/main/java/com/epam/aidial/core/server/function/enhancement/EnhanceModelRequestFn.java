package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnhanceModelRequestFn extends BaseRequestFunction<RequestObject> {
    public EnhanceModelRequestFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        Deployment deployment = context.getDeployment();
        if (deployment instanceof Model) {
            return enhanceModelRequest(context, request);
        }
        return false;
    }

    private static boolean enhanceModelRequest(ProxyContext context, RequestObject request) {
        Model model = (Model) context.getDeployment();
        String overrideName = model.getOverrideName();

        if (overrideName == null) {
            return false;
        }

        request.setModel(overrideName);

        return true;
    }
}
