package com.epam.aidial.core.server.data;

import com.epam.aidial.core.storage.data.ShareMetadata;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    public void addSharedResources(List<SharedResource> sharedResources, String sharedByDisplayName, long time) {
        Map<String, SharedResource> resourcesMap = resources.stream().collect(Collectors.toMap(SharedResource::getUrl, r -> r));
        for (SharedResource sharedResource : sharedResources) {
            SharedResource existingResource = resourcesMap.get(sharedResource.getUrl());
            ShareMetadata shareMetadata = new ShareMetadata();
            shareMetadata.setAcceptedAt(time);
            shareMetadata.setUser(sharedByDisplayName);
            shareMetadata.setPermissions(sharedResource.getPermissions());
            if (existingResource == null) {
                List<ShareMetadata> sharedBy = new ArrayList<>();
                sharedBy.add(shareMetadata);
                sharedResource.setSharedBy(sharedBy);
                this.resources.add(sharedResource);
            } else {
                existingResource.getPermissions().addAll(sharedResource.getPermissions());
                List<ShareMetadata> sharedBy = existingResource.getSharedBy();
                if (sharedBy == null) {
                    sharedBy = new ArrayList<>();
                    existingResource.setSharedBy(sharedBy);
                }

                sharedBy.add(shareMetadata);
            }
        }
    }
}
