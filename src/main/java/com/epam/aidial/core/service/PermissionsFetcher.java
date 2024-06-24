package com.epam.aidial.core.service;

import com.epam.aidial.core.ProxyContext;
import com.epam.aidial.core.data.ResourceAccessType;
import com.epam.aidial.core.security.AccessService;
import com.epam.aidial.core.storage.ResourceDescription;

import java.util.Map;
import java.util.Set;

@FunctionalInterface
public interface PermissionsFetcher {
    PermissionsFetcher EMPTY = resources -> Map.of();

    Map<ResourceDescription, Set<ResourceAccessType>> fetch(Set<ResourceDescription> resources);

    static PermissionsFetcher of(ProxyContext context, AccessService accessService) {
        return Boolean.parseBoolean(context.getRequest().getParam("permissions", "false"))
                ? resources -> accessService.lookupPermissions(resources, context)
                : EMPTY;
    }
}
