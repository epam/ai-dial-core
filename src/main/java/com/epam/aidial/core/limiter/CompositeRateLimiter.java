package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class CompositeRateLimiter implements RateLimiter {

    private final ApiKeyRateLimiter apiKeyRateLimiter;
    private final UserRateLimiter userRateLimiter;

    @Override
    public boolean register(ProxyContext context) {
        if (context.getKey() != null) {
            return apiKeyRateLimiter.register(context);
        } else {
            return userRateLimiter.register(context);
        }
    }

    @Override
    public void unregister(ProxyContext context) {
        apiKeyRateLimiter.unregister(context);
        userRateLimiter.unregister(context);
    }

    @Override
    public void increase(ProxyContext context) {
        if (context.getKey() != null) {
            apiKeyRateLimiter.increase(context);
        } else {
            userRateLimiter.increase(context);
        }
    }

    @Override
    public RateLimitResult limit(ProxyContext context) {
        if (context.getKey() != null) {
            return apiKeyRateLimiter.limit(context);
        } else {
            return userRateLimiter.limit(context);
        }
    }
}
