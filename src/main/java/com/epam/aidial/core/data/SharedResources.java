package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Data
public class SharedResources {
    @JsonProperty("resources")
    Set<ResourceLink> readableResources;
    Map<String, Set<ResourceAccessType>> resourcesWithPermissions;

    @JsonCreator
    public SharedResources(
            @JsonProperty("resources")
            Set<ResourceLink> readableResources,
            @JsonProperty("resourcesWithPermissions")
            Map<String, Set<ResourceAccessType>> resourcesWithPermissions) {
        this.readableResources = readableResources;
        this.resourcesWithPermissions = resourcesWithPermissions == null
                ? oldSetToReadPermissions(readableResources)
                : resourcesWithPermissions;
    }

    private static Map<String, Set<ResourceAccessType>> oldSetToReadPermissions(
            Set<ResourceLink> readableResources) {
        return readableResources.stream()
                .collect(Collectors.toMap(ResourceLink::url, link -> EnumSet.of(ResourceAccessType.READ)));
    }
}
