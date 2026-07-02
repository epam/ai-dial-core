package com.epam.aidial.core.server.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public record ExtractedClaims(String userId, List<String> userRoles, String userHash,
                              ObjectNode userClaims, String project, String userDisplayName) {

    /**
     * The token's authorized party — the calling workload's client id: the {@code azp} claim, or {@code appid}
     * (Azure v1) as a fallback. {@code null} when neither is present.
     */
    public String authorizedParty() {
        if (userClaims == null) {
            return null;
        }
        JsonNode azp = userClaims.get("azp");
        if (azp == null || azp.isNull()) {
            azp = userClaims.get("appid");
        }
        return azp == null || azp.isNull() ? null : azp.asText();
    }
}
