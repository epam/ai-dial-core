package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;

public interface RateLimiter {

    boolean register(ProxyContext context);

    void unregister(ProxyContext context);

    void increase(ProxyContext context);

    RateLimitResult limit(ProxyContext context);
}
