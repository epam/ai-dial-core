package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class CollectRequestDataFn extends BaseRequestFunction<ObjectNode> {
    public CollectRequestDataFn(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }

    @Override
    public Throwable apply(ObjectNode tree) {
        JsonNode stream = tree.get("stream");
        boolean result = stream != null && stream.asBoolean(false);
        context.setStreamingRequest(result);
        return null;
    }
}
