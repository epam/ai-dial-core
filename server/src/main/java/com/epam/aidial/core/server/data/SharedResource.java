package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.EnumSet;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SharedResource {
    String url;
    String sharedBy;
    Set<ResourceAccessType> permissions;

    public SharedResource() {
    }

    public SharedResource(String url, String sharedBy, Set<ResourceAccessType> permissions) {
        this.url = url;
        this.sharedBy = sharedBy;
        this.permissions = permissions;
    }

    public SharedResource withUrl(String url) {
        return new SharedResource(url, sharedBy, permissions);
    }

    public SharedResource withSharedBy(String sharedBy) {
        return new SharedResource(url, sharedBy, permissions);
    }

    private SharedResource withPermissions(Set<ResourceAccessType> permissions) {
        return new SharedResource(url, sharedBy, permissions);
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
