package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class ResourceItemMetadata extends MetadataBase {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long createdAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long updatedAt;

    public ResourceItemMetadata(ResourceType type, String bucket, String name, String path, String url) {
        super(name, path, bucket, url, NodeType.ITEM, type, null);
    }

    public ResourceItemMetadata(ResourceDescription resource) {
        this(resource.getType(), resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl());
    }
}
