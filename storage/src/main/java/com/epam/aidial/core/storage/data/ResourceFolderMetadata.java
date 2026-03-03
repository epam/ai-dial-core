package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
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

    private ResourceFolderMetadata(ResourceDescriptor descriptor, ResourceType type, String bucket,
                                   String name, String path, String url, List<MetadataBase> items) {
        super(descriptor, name, path, bucket, url, NodeType.FOLDER, type, null, null, null);
        this.items = items;
    }

    public ResourceFolderMetadata(ResourceDescriptor resource) {
        this(resource, null, null);
    }

    public ResourceFolderMetadata(ResourceDescriptor resource, List<MetadataBase> items, String nextToken) {
        this(resource, resource.getType(), resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl(), items);
        this.nextToken = nextToken;
    }
}
