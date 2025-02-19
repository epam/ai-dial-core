package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.storage.resource.ResourceDescriptor;
import com.epam.aidial.core.storage.resource.ResourceType;
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

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String etag;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String author;

    public ResourceItemMetadata(ResourceType type, String bucket, String name, String path, String url) {
        super(name, path, bucket, url, NodeType.ITEM, type, null);
    }

    public ResourceItemMetadata(ResourceDescriptor resource) {
        this(resource.getType(), resource.getBucketName(), resource.getName(), resource.getParentPath(), resource.getUrl());
    }
}
