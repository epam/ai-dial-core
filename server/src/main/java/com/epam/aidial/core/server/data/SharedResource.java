package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.storage.data.ShareMetadata;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SharedResource {
    String url;
    String author;
    /**
     * The list of users or projects who shared this resource with the current user.
     */
    List<ShareMetadata> sharedBy;
    Set<ResourceAccessType> permissions;

    public SharedResource() {
    }

    public SharedResource(
            String url, String author, List<ShareMetadata> sharedBy, Set<ResourceAccessType> permissions) {
        this.url = url;
        this.author = author;
        this.sharedBy = sharedBy;
        this.permissions = permissions;
    }

    public SharedResource withUrl(String url) {
        return new SharedResource(url, author, sharedBy, permissions);
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
