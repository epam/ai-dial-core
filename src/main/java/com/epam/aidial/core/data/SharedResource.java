package com.epam.aidial.core.data;

import java.util.Set;

public record SharedResource(
        String url,
        Set<ResourceAccessType> permissions) {
    public SharedResource withUrl(String url) {
        return new SharedResource(url, permissions);
    }

    public SharedResource withPermissions(Set<ResourceAccessType> permissions) {
        return new SharedResource(url, permissions);
    }

    public SharedResource withReadIfNoPermissions() {
        return permissions == null || permissions.isEmpty()
                ? withPermissions(ResourceAccessType.READ_ONLY)
                : this;
    }

    public SharedResource withAllIfNoPermissions() {
        return permissions == null || permissions.isEmpty()
                ? withPermissions(ResourceAccessType.ALL)
                : this;
    }
}
