package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.EnumSet;
import java.util.List;
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

    public Set<ResourceAccessType> lookupPermissions(String url) {
        return resources.stream()
                .filter(resource -> url.equals(resource.url()))
                .map(SharedResource::permissions)
                .reduce(EnumSet.noneOf(ResourceAccessType.class), (a, b) -> {
                    a.addAll(b);
                    return a;
                });
    }
}
