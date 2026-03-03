package com.epam.aidial.core.server.security;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;

public record ExtractedClaims(String userId, List<String> userRoles, String userHash,
                              ObjectNode userClaims, String project, String userDisplayName) {
}
