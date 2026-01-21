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
    /**
     * The maximum number of users can accept the invitation link.
     * The default value is unset <code>-1</code>.
     */
    @JsonAlias({"maxAcceptedUsers", "max_accepted_users"})
    int maxAcceptedUsers = -1;
    /**
     * The time to live of the invitation link is measured in hours.
     * The default value is unset <code>-1</code>.
     */
    @JsonAlias({"invitationTtl", "invitation_ttl"})
    long invitationTtl = -1;

    public ShareResourceLimit() {

    }

    public ShareResourceLimit(int maxAcceptedUsers, long invitationTtl) {
        this.maxAcceptedUsers = maxAcceptedUsers;
        this.invitationTtl = invitationTtl;
    }
}
