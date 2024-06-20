package com.epam.aidial.core.data;

import com.epam.aidial.core.storage.ResourceDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Set;

@NoArgsConstructor
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(chain = true)
public class ResourceItemMetadata extends MetadataBase {

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long createdAt;

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Long updatedAt;

    public ResourceItemMetadata(
            ResourceType type,
            String bucket,
            String name,
            String path,
            String url,
            Set<ResourceAccessType> permissions) {
        super(name, path, bucket, url, NodeType.ITEM, type, permissions);
    }

    public ResourceItemMetadata(ResourceDescription resource, Set<ResourceAccessType> permissions) {
        this(
                resource.getType(),
                resource.getBucketName(),
                resource.getName(),
                resource.getParentPath(),
                resource.getUrl(),
                permissions);
    }
}
