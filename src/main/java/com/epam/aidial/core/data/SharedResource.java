package com.epam.aidial.core.data;

import java.util.EnumSet;
import java.util.Set;

public record SharedResource(
        String url,
        Set<ResourceAccessType> permissions) {
    public SharedResource {
        if (permissions == null) {
            permissions = EnumSet.of(ResourceAccessType.READ);
        }
    }

    public SharedResource withUrl(String url) {
        return new SharedResource(url, permissions);
    }

    public SharedResource withPermissions(Set<ResourceAccessType> permissions) {
        return new SharedResource(url, permissions);
    }
}
