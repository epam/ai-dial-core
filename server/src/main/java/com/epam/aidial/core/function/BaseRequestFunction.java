package com.epam.aidial.core.function;

import com.epam.aidial.core.Proxy;
import com.epam.aidial.core.ProxyContext;

public abstract class BaseRequestFunction<T> extends BaseFunction<T, Throwable> {


    public BaseRequestFunction(Proxy proxy, ProxyContext context) {
        super(proxy, context);
    }
}
