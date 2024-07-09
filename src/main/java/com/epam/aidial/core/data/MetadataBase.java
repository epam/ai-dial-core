package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Set;

@AllArgsConstructor
@Data
@NoArgsConstructor
public abstract class MetadataBase {
    public static final String MIME_TYPE = "application/vnd.dial.metadata+json";

    private String name;
    private String parentPath;
    private String bucket;
    private String url;
    private NodeType nodeType;
    private ResourceType resourceType;
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Set<ResourceAccessType> permissions;
}
