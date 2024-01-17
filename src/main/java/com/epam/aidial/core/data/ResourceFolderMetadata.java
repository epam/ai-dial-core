package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ResourceFolderMetadata extends MetadataBase {

    private List<? extends MetadataBase> items;

    public ResourceFolderMetadata(ResourceType type, String bucket, String name, String path, String url, List<MetadataBase> items) {
        super(name, path, bucket, url, NodeType.FOLDER, type);
        this.items = items;
    }

    public ResourceFolderMetadata(ResourceType type, String bucket, String name, String path, String url) {
        this(type, bucket, name, path, url, null);
    }

    public ResourceFolderMetadata(ResourceDescription resource) {
        this(resource, null);
    }

    public ResourceFolderMetadata(ResourceDescription resource, List<MetadataBase> items) {
        this(resource.getType(), resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl(), items);
    }
}
