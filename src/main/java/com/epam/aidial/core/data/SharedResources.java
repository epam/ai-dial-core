package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.Sets;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Data
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

    public Set<ResourceAccessType> findPermissions(String url) {
        return resources.stream()
                .filter(resource -> url.equals(resource.url()))
                .map(SharedResource::permissions)
                .reduce(Set.of(), Sets::union);
    }

    public Map<String, Set<ResourceAccessType>> toMap() {
        return resources.stream()
                .collect(Collectors.toUnmodifiableMap(SharedResource::url, SharedResource::permissions, Sets::union));
    }
}
