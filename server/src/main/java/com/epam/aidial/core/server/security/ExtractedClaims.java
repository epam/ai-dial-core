package com.epam.aidial.core.server.security;

import java.util.List;
import java.util.Map;

public record ExtractedClaims(String sub, List<String> userRoles, String userHash, Map<String, List<String>> userClaims) {
}
