package com.epam.aidial.core.server.config;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceTypes;

import java.util.List;

/** Default single-tenant strategy — design 02 §5.1 bucket policy. */
public class PlatformEntityLocationStrategy implements EntityLocationStrategy {

    @Override
    public String resolveBucket(ResourceTypes entityType, String scope) {
        if (!PLATFORM_SCOPE.equals(scope)) {
            throw new IllegalArgumentException("Unsupported scope for platform strategy: " + scope);
        }
        return switch (entityType) {
            case INTERCEPTOR, ROLE, PROJECT_KEY, ROUTE, GLOBAL_SETTINGS, MODEL, APP_TYPE_SCHEMA, CATALOG_SCHEMA -> ResourceDescriptor.PLATFORM_BUCKET;
            default -> throw new IllegalArgumentException("Unsupported entity type for platform strategy: " + entityType);
        };
    }

    @Override
    public List<String> listScopes(ResourceTypes entityType) {
        return List.of(PLATFORM_SCOPE);
    }
}
