package com.epam.aidial.core.security;

import java.util.List;

public record ExtractedClaims(List<String> userRoles, String userHash) { }
