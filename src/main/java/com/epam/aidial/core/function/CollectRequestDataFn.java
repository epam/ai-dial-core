package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CollectRequestDataFn extends BaseFunction<ObjectNode> {
    public CollectRequestDataFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        context.setStreamingRequest(tree.get("stream").asBoolean(false));
        return null;
    }
}
