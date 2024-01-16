package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
public class ResourceMetadataCollection extends MetadataBase {

    private List<? extends MetadataBase> items;

    public ResourceMetadataCollection(ResourceType type, String bucket, String name, String path, String url, List<MetadataBase> items) {
        super(name, path, bucket, url, NodeType.FOLDER, type);
        this.items = items;
    }

    public ResourceMetadataCollection(ResourceType type, String bucket, String name, String path, String url) {
        this(type, bucket, name, path, url, null);
    }

    public ResourceMetadataCollection(ResourceDescription resource) {
        this(resource, null);
    }

    public ResourceMetadataCollection(ResourceDescription resource, List<MetadataBase> items) {
        this(resource.getType(), resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl(), items);
    }
}
