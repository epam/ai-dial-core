package com.epam.aidial.core.mcp.client;

import io.vertx.core.MultiMap;

public record DialResponse(int statusCode, String body, MultiMap headers) {
}
