package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;


@Data
public class RevokeResourcesRequest {
    List<SharedResource> resources;

    @JsonCreator
    public RevokeResourcesRequest(@JsonProperty("resources") List<SharedResource> resources) {
        this.resources = resources.stream()
                .map(SharedResource::withAllIfNoPermissions)
                .toList();
    }
}
