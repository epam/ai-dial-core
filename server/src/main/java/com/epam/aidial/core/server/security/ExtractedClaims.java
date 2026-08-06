package com.epam.aidial.core.server.security;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

// tolerate members written by a newer core version, so per-request keys survive a rolling upgrade
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractedClaims(String userId, List<String> userRoles, String userHash,
                              ObjectNode userClaims, String project, String userDisplayName, String userEmail) {

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
