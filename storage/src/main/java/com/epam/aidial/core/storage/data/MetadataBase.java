package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Set;

@AllArgsConstructor
@Data
@NoArgsConstructor
@Accessors(chain = true)
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
    /**
     * The list of users (display names or project names) who shared the resource with the current user.
     * This is populated when listing shares with "with": "me".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ShareMetadata> sharedBy;
    /**
     * The list of users (display names or project names) with whom the resource is shared.
     * This is populated when listing shares with "with": "others".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private List<ShareMetadata> sharedWith;
}
