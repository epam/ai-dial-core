package com.epam.aidial.core.data;

import com.epam.aidial.core.util.ResourceUtil;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Sets;
import lombok.Data;

import java.util.EnumSet;
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

    public void addSharedResources(Map<String, Set<ResourceAccessType>> sharedResources) {
        Map<String, Set<ResourceAccessType>> resourcesMap = ResourceUtil.sharedResourcesToMap(resources);
        sharedResources.forEach((url, permissions) -> {
            Set<ResourceAccessType> existingPermissions = resourcesMap.get(url);
            if (existingPermissions == null) {
                existingPermissions = EnumSet.noneOf(ResourceAccessType.class);
                this.resources.add(new SharedResource(url, existingPermissions));
            }

            existingPermissions.addAll(permissions);
        });
    }

    public Set<ResourceAccessType> findPermissions(String url) {
        return resources.stream()
                .filter(resource -> url.equals(resource.url()))
                .map(SharedResource::permissions)
                .reduce(Set.of(), Sets::union);
    }
}
