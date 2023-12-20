package com.epam.aidial.core.limiter;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.config.Deployment;
import com.epam.aidial.core.config.Key;
import com.epam.aidial.core.config.Limit;
import com.epam.aidial.core.config.Role;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class ApiKeyRateLimiter extends BaseRateLimiter<Key> {

    @Override
    protected Key getEntity(ProxyContext context) {
        return context.getKey();
    }

    @Override
    protected String getEntityId(ProxyContext context) {
        Key key = context.getKey();
        if (key == null || key.getRole() == null) {
            return null;
        }
        return key.getKey();
    }

    @Override
    protected Limit getLimit(ProxyContext context) {
        Key key = getEntityFromTracingContext();
        if (key == null) {
            log.warn("Key is not found from the tracing context");
            return null;
        }
        Role role = context.getConfig().getRoles().get(key.getRole());

        if (role == null) {
            log.warn("Role is not found for key: {}", context.getKey().getKey());
            return null;
        }

        Deployment deployment = context.getDeployment();
        return role.getLimits().get(deployment.getName());
    }

}