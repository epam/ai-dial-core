package com.epam.aidial.core.data;

import lombok.Data;

import java.util.Set;

@Data
public class ListSharedResourcesRequest {
    Set<ResourceType> resourceTypes;
    String order;
    String with;
}
