package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.security.ExtractedClaims;

public class UserRateLimiter extends BaseRateLimiter<ExtractedClaims> {
    @Override
    protected ExtractedClaims getEntity(ProxyContext context) {
        if (context.getUserSub() == null) {
            return null;
        }
        return new ExtractedClaims(context.getUserSub(), context.getUserRoles(), context.getUserHash());
    }

    @Override
    protected String getEntityId(ProxyContext context) {
        return context.getUserSub();
    }

    @Override
    protected Limit getLimit(ProxyContext context) {
        // TODO not implemented yet
        return new Limit();
    }

}
