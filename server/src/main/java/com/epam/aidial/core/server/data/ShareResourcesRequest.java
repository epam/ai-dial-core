package com.epam.aidial.core.server.data;

import lombok.Data;

import java.util.Set;

@Data
public class ShareResourcesRequest {
    Set<SharedResource> resources;
    InvitationType invitationType;
    int maxAcceptedUsers = Integer.MAX_VALUE;
}
