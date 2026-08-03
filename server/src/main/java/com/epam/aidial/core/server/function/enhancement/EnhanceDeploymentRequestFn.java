package com.epam.aidial.core.server.function.enhancement;

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
        String overrideName = context.getDeployment().getOverrideName();

        if (overrideName == null) {
            return false;
        }

        request.setModel(overrideName);

        return true;
    }
}
