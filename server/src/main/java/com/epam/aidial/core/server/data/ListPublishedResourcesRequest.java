package com.epam.aidial.core.server.data;

import com.epam.aidial.core.storage.resource.ResourceTypes;
import lombok.Data;

import java.util.Set;

@Data
public class ListPublishedResourcesRequest {
    Set<ResourceTypes> resourceTypes;
}
