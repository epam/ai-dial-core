package com.epam.aidial.core.server.function;

import com.epam.aidial.core.server.Proxy;
import com.epam.aidial.core.server.ProxyContext;

public abstract class BaseRequestFunction<T> extends BaseFunction<T, Throwable> {


    public BaseRequestFunction(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }
}
