package com.epam.aidial.core.server.data;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.Data;

import java.util.Set;

@Data
public class ListSharedResourcesRequest {
    /**
     * Collection of resource types that user want to list
     */
    Set<ResourceTypes> resourceTypes;
    /**
     * Sorting order. Not implemented yet
     */
    String order;
    /**
     * Shared resource direction. Can be either with - me or others.
     */
    String with;
    /**
     * Include user display name in the response
     */
    boolean includeUserInfo;
}
