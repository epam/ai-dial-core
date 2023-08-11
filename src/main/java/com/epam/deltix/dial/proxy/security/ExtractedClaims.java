package com.epam.deltix.dial.proxy.security;

import java.util.List;

public record ExtractedClaims(List<String> userRoles, String userHash) { }
