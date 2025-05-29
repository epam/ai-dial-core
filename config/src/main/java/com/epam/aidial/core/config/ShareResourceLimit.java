package com.epam.aidial.core.config;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public class ShareResourceLimit {
    @JsonAlias({"maxAcceptedUsers", "max_accepted_users"})
    int maxAcceptedUsers;
    @JsonAlias({"invitationTtl", "invitation_ttl"})
    long invitationTtl;

    public ShareResourceLimit() {

    }

    public ShareResourceLimit(int maxAcceptedUsers, long invitationTtl) {
        this.maxAcceptedUsers = maxAcceptedUsers;
        this.invitationTtl = invitationTtl;
    }

    public ShareResourceLimit(long invitationTtl) {
        this.maxAcceptedUsers = Integer.MAX_VALUE;
        this.invitationTtl = invitationTtl;
    }
}
