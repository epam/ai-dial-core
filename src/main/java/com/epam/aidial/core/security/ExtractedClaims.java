package com.epam.aidial.core.security;

import java.util.List;

public record ExtractedClaims(String sub, List<String> userRoles, String userHash) {
}
