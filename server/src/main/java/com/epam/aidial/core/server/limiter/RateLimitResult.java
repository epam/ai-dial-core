package com.epam.aidial.core.server.limiter;

import com.epam.aidial.core.storage.http.HttpStatus;

public record RateLimitResult(HttpStatus status, String errorMessage, long replyAfterSeconds) {
    public static final RateLimitResult SUCCESS = new RateLimitResult(HttpStatus.OK, null, -1);
}
