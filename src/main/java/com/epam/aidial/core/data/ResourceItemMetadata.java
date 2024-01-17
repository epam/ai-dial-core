package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import lombok.NoArgsConstructor;

@NoArgsConstructor
public abstract class ResourceItemMetadata extends MetadataBase {

    public ResourceItemMetadata(ResourceType type, String bucket, String name, String path, String url) {
        super(name, path, bucket, url, NodeType.ITEM, type);
    }

    public ResourceItemMetadata(ResourceDescription resource) {
        this(resource.getType(), resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl());
    }
}
