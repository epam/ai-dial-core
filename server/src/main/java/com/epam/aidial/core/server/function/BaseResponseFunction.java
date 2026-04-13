package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;
import com.fasterxml.jackson.databind.JsonNode;
import io.vertx.core.Future;

public abstract class BaseResponseFunction extends BaseFunction<JsonNode, Future<JsonNode>> {
    public BaseResponseFunction(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }
}
