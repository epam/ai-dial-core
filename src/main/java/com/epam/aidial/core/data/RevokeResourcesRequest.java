package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Set;
import java.util.stream.Collectors;

@Data
public class RevokeResourcesRequest {
    Set<SharedResource> resources;

    @JsonCreator
    public RevokeResourcesRequest(@JsonProperty("resources") Set<SharedResource> resources) {
        this.resources = resources.stream()
                .map(SharedResource::withAllIfNoPermissions)
                .collect(Collectors.toSet());
    }
}
