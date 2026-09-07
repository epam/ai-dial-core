package com.epam.aidial.core.server.function.enhancement;

import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Model;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.BaseRequestFunction;
import com.epam.aidial.core.server.function.request.RequestObject;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class EnhanceDeploymentRequestFn extends BaseRequestFunction<RequestObject> {
    public EnhanceDeploymentRequestFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Boolean apply(RequestObject request) {
        Deployment deployment = context.getDeployment();
        String overrideName = deployment.getOverrideName();

        if (overrideName != null) {
            request.setModel(overrideName);
            return true;
        }

        // Model routing - and with it pricing and limits - is the core's domain: a model is called under
        // the id the config gives it, never under the name the client put in the body. Applications and
        // interceptors keep passing the body through untouched.
        if (deployment instanceof Model) {
            request.setModel(deployment.getName());
            return true;
        }

        return false;
    }
}
