package com.epam.aidial.core.mcp.ratelimit;

public sealed interface Decision permits Decision.Allow, Decision.Deny {

    record Allow() implements Decision {
    }

    record Deny(long retryAfterSeconds) implements Decision {
    }
}
