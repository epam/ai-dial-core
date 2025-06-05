package com.epam.aidial.core.server.data;

import com.epam.aidial.core.storage.data.ResourceAccessType;
import lombok.Data;

import java.util.EnumSet;
import java.util.Set;

@Data
public class SharedResource {
    String url;
    Set<ResourceAccessType> permissions;
    boolean canReshare;

    public SharedResource() {
    }

    public SharedResource(String url, Set<ResourceAccessType> permissions, boolean canReshare) {
        this.url = url;
        this.permissions = permissions;
        this.canReshare = canReshare;
    }

    public SharedResource withUrl(String url) {
        return new SharedResource(url, permissions, canReshare);
    }

    public SharedResource withPermissions(Set<ResourceAccessType> permissions) {
        return new SharedResource(url, permissions, canReshare);
    }

    public SharedResource withReadIfNoPermissions() {
        return permissions == null || permissions.isEmpty()
                ? withPermissions(EnumSet.copyOf(ResourceAccessType.READ_ONLY))
                : this;
    }

    public SharedResource withAllIfNoPermissions() {
        return permissions == null || permissions.isEmpty()
                ? withPermissions(EnumSet.copyOf(ResourceAccessType.ALL))
                : this;
    }
}
