package com.epam.aidial.core.service;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.storage.ResourceDescription;

import java.util.Set;

@FunctionalInterface
public interface PermissionsFetcher {
    PermissionsFetcher NULL = resource -> null;

    Set<ResourceAccessType> fetch(ResourceDescription resource);

    static PermissionsFetcher of(ProxyContext context, AccessService accessService) {
        return Boolean.parseBoolean(context.getRequest().getParam("permissions", "false"))
                ? resource -> accessService.lookupPermissions(resource, context)
                : NULL;
    }
}
