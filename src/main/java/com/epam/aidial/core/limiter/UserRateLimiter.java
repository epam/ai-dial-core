package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.security.ExtractedClaims;

public class UserRateLimiter extends BaseRateLimiter<ExtractedClaims> {
    @Override
    protected ExtractedClaims getEntity(ProxyContext context) {
        return new ExtractedClaims(context.getUserSub(), context.getUserRoles(), context.getUserHash());
    }

    @Override
    public void increase(ProxyContext context) {
        //TODO not implemented yet
    }

    @Override
    public RateLimitResult limit(ProxyContext context) {
        //TODO not implemented yet
        return RateLimitResult.SUCCESS;
    }
}
