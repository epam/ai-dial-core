package com.epam.aidial.core.storage.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.epam.aidial.core.storage.resource.ResourceType;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.util.Map;
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
     * The display name (API key project name, or extracted from JWT) of the user who shared the resource.
     * This is populated when listing shares with "with": "me".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String sharedBy;
    /**
     * A map from user display names to the set of access types each user has for this resource.
     * This is populated when listing shares with "with": "others".
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private Map<String, Set<ResourceAccessType>> sharedWith;
}
