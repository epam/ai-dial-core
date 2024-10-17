package com.epam.aidial.core.data;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.Set;
import java.util.stream.Collectors;

@Data
public class ShareResourcesRequest {
    Set<SharedResource> resources;
    InvitationType invitationType;

    @JsonCreator
    public ShareResourcesRequest(
            @JsonProperty("resources") Set<SharedResource> resources,
            @JsonProperty("invitationType") InvitationType invitationType) {
        this.resources = resources.stream()
                .map(SharedResource::withReadIfNoPermissions)
                .collect(Collectors.toSet());
        this.invitationType = invitationType;
    }
}
