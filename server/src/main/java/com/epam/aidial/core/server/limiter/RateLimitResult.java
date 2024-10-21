package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.server.util.HttpStatus;

public record RateLimitResult(HttpStatus status, String errorMessage) {
    public static final RateLimitResult SUCCESS = new RateLimitResult(HttpStatus.OK, null);
}
