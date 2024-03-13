package com.epam.aidial.core.data;

import lombok.Data;

import java.util.Set;

@Data
public class ListSharedResourcesRequest {
    /**
     * Collection of resource types that user want to list
     */
    Set<ResourceType> resourceTypes;
    /**
     * Sorting order. Not implemented yet
     */
    String order;
    /**
     * Shared resource direction. Can be either with - me or others.
     */
    String with;
}
