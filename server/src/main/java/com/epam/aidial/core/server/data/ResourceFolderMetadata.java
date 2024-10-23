package com.epam.aidial.core.server.data;

import com.epam.aidial.core.server.resource.ResourceDescriptor;
import com.epam.aidial.core.server.resource.ResourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@Accessors(chain = true)
public class ResourceFolderMetadata extends MetadataBase {

    private List<? extends MetadataBase> items;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String nextToken;

    public ResourceFolderMetadata(ResourceType type, String bucket, String name, String path, String url, List<MetadataBase> items) {
        super(name, path, bucket, url, NodeType.FOLDER, type, null);
        this.items = items;
    }

    public ResourceFolderMetadata(ResourceTypes type, String bucket, String name, String path, String url) {
        this(type, bucket, name, path, url, null);
    }

    public ResourceFolderMetadata(ResourceDescriptor resource) {
        this(resource, null, null);
    }

    public ResourceFolderMetadata(ResourceDescriptor resource, List<MetadataBase> items, String nextToken) {
        this(resource.getType(), resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl(), items);
        this.nextToken = nextToken;
    }
}
