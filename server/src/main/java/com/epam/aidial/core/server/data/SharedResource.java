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
    Set<ResourceAccessType> permissions;

    public SharedResource() {
    }

    public SharedResource(String url, Set<ResourceAccessType> permissions) {
        this.url = url;
        this.permissions = permissions;
    }

    public SharedResource withUrl(String url) {
        return new SharedResource(url, permissions);
    }

    public SharedResource withPermissions(Set<ResourceAccessType> permissions) {
        return new SharedResource(url, permissions);
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
