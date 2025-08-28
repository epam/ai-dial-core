package com.epam.aidial.core.server.data;

import com.epam.aidial.core.config.ResourceAccessType;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Sets;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class SharedResources {
    List<SharedResource> resources;

    @JsonCreator
    public SharedResources(
            @JsonProperty("resources")
            List<SharedResource> resources) {
        this.resources = resources.stream()
                .map(SharedResource::withReadIfNoPermissions)
                .collect(Collectors.toList());
    }

    public void addSharedResources(List<SharedResource> sharedResources, String sharedByDisplayName) {
        Map<String, SharedResource> resourcesMap = resources.stream().collect(Collectors.toMap(SharedResource::getUrl, r -> r));
        for (SharedResource sharedResource : sharedResources) {
            SharedResource existingResource = resourcesMap.get(sharedResource.getUrl());
            if (existingResource == null) {
                this.resources.add(sharedResource.withSharedByDisplayName(sharedByDisplayName));
            } else {
                existingResource.getPermissions().addAll(sharedResource.getPermissions());
                existingResource.setSharedByDisplayName(sharedByDisplayName);
            }
        }
    }

    public Set<ResourceAccessType> findPermissions(String url) {
        return resources.stream()
                .filter(resource -> url.equals(resource.getUrl()))
                .map(SharedResource::getPermissions)
                .reduce(Set.of(), Sets::union);
    }
}
