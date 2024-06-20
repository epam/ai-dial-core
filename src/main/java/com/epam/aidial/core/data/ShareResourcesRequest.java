package com.epam.aidial.core.data;

import lombok.Data;

import java.util.Set;

@Data
public class ShareResourcesRequest {
    Set<SharedResource> resources;
    InvitationType invitationType;
}
