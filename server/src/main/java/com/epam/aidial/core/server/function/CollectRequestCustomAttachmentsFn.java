package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.function.request.RequestObject;

import java.util.List;
import java.util.Set;

public class CollectRequestCustomAttachmentsFn extends CollectRequestAttachmentsFn {
    public CollectRequestCustomAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(RequestObject request) {
        Route route = context.getRoute();
        List<String> jsonPaths = route.getAttachmentPaths().getRequestBody();
        return request.collectAppAttachments(jsonPaths);
    }
}
