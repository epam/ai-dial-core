package com.epam.aidial.core.server.data;

import lombok.Data;

import java.util.Set;

@Data
public class ListPublishedResourcesRequest {
    Set<ResourceType> resourceTypes;
}
