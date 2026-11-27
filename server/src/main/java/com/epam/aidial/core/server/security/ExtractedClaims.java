package com.epam.aidial.core.server.security;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * @param userEmail         the user's address from the provider's {@code userEmailPath}; what a rule on {@code email} matches
 * @param costObjectPathSet whether the provider sets {@code costObjectPath}, so the claim alone decides the cost object.
 *                          Read by the root request only, and not stored with a per-request key, whose older readers
 *                          refuse an unknown field
 */
public record ExtractedClaims(String userId, List<String> userRoles, String userHash,
                              ObjectNode userClaims, String project, String userDisplayName, String userEmail,
                              @JsonIgnore boolean costObjectPathSet) {

    public ExtractedClaims(String userId, List<String> userRoles, String userHash,
                           ObjectNode userClaims, String project, String userDisplayName, String userEmail) {
        this(userId, userRoles, userHash, userClaims, project, userDisplayName, userEmail, false);
    }

    /**
     * The token's authorized party — the calling workload's client id from the {@code azp} claim, or {@code null}
     * when absent. Only {@code azp} is honored (not Azure v1 {@code appid}, which may be an interactive client).
     */
    public String authorizedParty() {
        if (userClaims == null) {
            return null;
        }
        JsonNode azp = userClaims.get("azp");
        // Treat a present-but-blank claim as absent so it can never match a (mis)configured blank app_identity.
        return azp == null ? null : StringUtils.trimToNull(azp.asText());
    }
}
