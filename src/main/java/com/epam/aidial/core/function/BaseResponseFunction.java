package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.vertx.core.Future;

public abstract class BaseResponseFunction extends BaseFunction<ObjectNode, Future<Void>> {
    public BaseResponseFunction(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }
}
