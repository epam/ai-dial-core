package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.EnumSet;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SharedResource {
    String url;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String author;
    /**
     * Display name of the user who shared the resource with the current user.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String sharedBy;
    Set<ResourceAccessType> permissions;

    public SharedResource() {
    }

    public SharedResource(
            String url, String author, String sharedBy, Set<ResourceAccessType> permissions) {
        this.url = url;
        this.author = author;
        this.sharedBy = sharedBy;
        this.permissions = permissions;
    }

    public SharedResource withUrl(String url) {
        return new SharedResource(url, author, sharedBy, permissions);
    }

    public SharedResource withSharedBy(String displayName) {
        return new SharedResource(url, author, displayName, permissions);
    }

    public SharedResource withAuthor(String name) {
        return new SharedResource(url, name, sharedBy, permissions);
    }

    private SharedResource withPermissions(Set<ResourceAccessType> permissions) {
        return new SharedResource(url, author, sharedBy, permissions);
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
