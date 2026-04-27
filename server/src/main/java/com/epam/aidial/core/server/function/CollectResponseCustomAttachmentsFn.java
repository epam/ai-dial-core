package com.epam.aidial.core.server.function;

import com.epam.aidial.core.config.Route;
import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.epam.aidial.core.server.util.ChatUtil;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Set;

public class CollectResponseCustomAttachmentsFn extends CollectResponseAttachmentsFn {
    public CollectResponseCustomAttachmentsFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    protected Set<String> collectAttachments(ObjectNode tree) {
        Route route = context.getRoute();
        List<String> jsonPaths = route.getAttachmentPaths().getResponseBody();
        return ChatUtil.collectAttachments(tree, jsonPaths);
    }
}
