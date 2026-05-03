package com.epam.aidial.core.server.config;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.List;

/** Default single-tenant strategy — design 02 §5.1 bucket policy. */
public class PlatformEntityLocationStrategy implements EntityLocationStrategy {

    @Override
    public String resolveBucket(ResourceTypes entityType, String scope) {
        if (!PLATFORM_SCOPE.equals(scope)) {
            return null;
        }
        return switch (entityType) {
            case MODEL, APP_TYPE_SCHEMA -> ResourceDescriptor.PUBLIC_BUCKET;
            case INTERCEPTOR, ROLE, PROJECT_KEY, ROUTE, GLOBAL_SETTINGS -> ResourceDescriptor.PLATFORM_BUCKET;
            default -> null;
        };
    }

    @Override
    public List<String> listScopes(ResourceTypes entityType) {
        return List.of(PLATFORM_SCOPE);
    }
}
