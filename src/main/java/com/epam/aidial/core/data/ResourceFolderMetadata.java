package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public class ResourceFolderMetadata extends MetadataBase {

    private List<? extends MetadataBase> items;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String nextToken;

    public ResourceFolderMetadata(
            ResourceType type,
            String bucket,
            String name,
            String path,
            String url,
            Set<ResourceAccessType> permissions,
            List<MetadataBase> items) {
        super(name, path, bucket, url, NodeType.FOLDER, type, permissions);
        this.items = items;
    }

    public ResourceFolderMetadata(
            ResourceType type,
            String bucket,
            String name,
            String path,
            String url,
            Set<ResourceAccessType> permissions) {
        this(type, bucket, name, path, url, permissions, null);
    }

    public ResourceFolderMetadata(ResourceDescription resource, Set<ResourceAccessType> permissions) {
        this(resource, permissions, null, null);
    }

    public ResourceFolderMetadata(
            ResourceDescription resource,
            Set<ResourceAccessType> permissions,
            List<MetadataBase> items,
            String nextToken) {
        this(
                resource.getType(),
                resource.getBucketName(),
                resource.getName(),
                resource.getParentPath(),
                resource.getUrl(),
                permissions,
                items);
        this.nextToken = nextToken;
    }
}
